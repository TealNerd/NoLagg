package com.bergerkiller.bukkit.nolagg.chunks;

import java.util.ArrayList;
import java.util.logging.Level;
import java.util.zip.Deflater;

import org.bukkit.entity.Player;

import com.bergerkiller.bukkit.common.AsyncTask;
import com.bergerkiller.bukkit.common.conversion.Conversion;
import com.bergerkiller.bukkit.common.protocol.CommonPacket;
import com.bergerkiller.bukkit.common.protocol.PacketType;
import com.bergerkiller.bukkit.common.reflection.FieldAccessor;
import com.bergerkiller.bukkit.common.reflection.MethodAccessor;
import com.bergerkiller.bukkit.common.reflection.SafeDirectMethod;
import com.bergerkiller.bukkit.common.reflection.SafeField;
import com.bergerkiller.bukkit.common.reflection.SafeMethod;
import com.bergerkiller.bukkit.common.reflection.classes.ChunkRef;
import com.bergerkiller.bukkit.common.reflection.classes.ChunkSectionRef;
import com.bergerkiller.bukkit.common.reflection.classes.NibbleArrayRef;
import com.bergerkiller.bukkit.common.reflection.classes.WorldRef;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.lishid.orebfuscator.internal.IPacket51;
import com.lishid.orebfuscator.internal.InternalAccessor;
import com.lishid.orebfuscator.obfuscation.Calculations;

public class ChunkCompressionThread extends AsyncTask {
	// create a cyclic player compression queue loop
	private static int queueIndex = 0;
	private static int idleCounter = 0;
	private static ChunkCompressionThread[] threads;
	private static ArrayList<ChunkCompressQueue> compress = new ArrayList<ChunkCompressQueue>();
	/**
	 * Maximum size a chunk packet can contain. This consists of:<br>
	 * - 16 slices, each containing 5 data sections of 2048 bytes in length<br>
	 * - Biome data, one byte for each column, 16 x 16
	 */
	private static final int MAX_CHUNK_DATA_LENGTH = (16 * 5 * 2048) + (16 * 16);
	private static final Class<?>[] SPIGOT_OBFUSCATE_ARGS = {int.class, int.class, int.class, byte[].class, WorldRef.TEMPLATE.getType()};
	private static MethodAccessor<Void> spigotObfuscateMethod;

	private static ChunkCompressQueue nextQueue() {
		synchronized (compress) {
			if (compress.isEmpty())
				return null;
			if (queueIndex > compress.size() - 1) {
				queueIndex = 0;
			}
			ChunkCompressQueue queue = compress.get(queueIndex);
			if (queue.isAlive()) {
				queueIndex++;
				return queue;
			} else {
				compress.remove(queueIndex);
				return nextQueue();
			}
		}
	}

	public static void addQueue(ChunkCompressQueue queue) {
		synchronized (compress) {
			compress.add(queue);
		}
	}

	public static void init(int threadcount) {
		if (threadcount <= 0) {
			NoLaggChunks.useBufferedLoading = false;
		}
		if (NoLaggChunks.useBufferedLoading) {
			if (threads != null) {
				if (threads.length > threadcount) {
					// kill some threads
					ChunkCompressionThread[] newthreads = new ChunkCompressionThread[threadcount];
					for (int i = 0; i < threads.length; i++) {
						if (i < newthreads.length) {
							newthreads[i] = threads[i];
						} else {
							threads[i].stop();
						}
					}
					threads = newthreads;
				} else if (threads.length < threadcount) {
					// Append new threads
					ChunkCompressionThread[] newthreads = new ChunkCompressionThread[threadcount];
					System.arraycopy(threads, 0, newthreads, 0, threads.length);
					for (int i = threads.length; i < newthreads.length; i++) {
						newthreads[i] = new ChunkCompressionThread();
						newthreads[i].start(true);
					}
					threads = newthreads;
				}
			} else {
				// Create all new threads
				threads = new ChunkCompressionThread[threadcount];
				for (int i = 0; i < threadcount; i++) {
					threads[i] = new ChunkCompressionThread();
					threads[i].start(true);
				}
			}
		} else if (threads != null) {
			for (ChunkCompressionThread thread : threads) {
				thread.stop();
			}
			threads = null;
		}
	}

	public static void deinit() {
		if (threads != null) {
			for (ChunkCompressionThread thread : threads) {
				thread.stop();
			}
			threads = null;
		}
	}

	private int rawLength = 0;
	private final byte[] rawbuffer = new byte[MAX_CHUNK_DATA_LENGTH];
	private final byte[] compbuffer = new byte[81920];
	private final Deflater deflater = new Deflater();

	private void rawAppend(byte[] data) {
		System.arraycopy(data, 0, this.rawbuffer, this.rawLength, data.length);
		this.rawLength += data.length;
	}

	public CommonPacket createPacket(org.bukkit.Chunk bchunk) {
		Object chunk = Conversion.toChunkHandle.convert(bchunk);
		Object world = ChunkRef.world.get(chunk);
		Object worldProvider = ChunkRef.worldProvider.get(world);
		boolean hasSkylight = !ChunkRef.hasSkyLight.get(worldProvider);
		// Version which uses the Chunkmap buffer to create the packet
		CommonPacket mapchunk = new CommonPacket(PacketType.OUT_MAP_CHUNK);
		PacketType.OUT_MAP_CHUNK.x.set(mapchunk.getHandle(), ChunkRef.x.get(chunk));
		PacketType.OUT_MAP_CHUNK.z.set(mapchunk.getHandle(), ChunkRef.z.get(chunk));
		PacketType.OUT_MAP_CHUNK.hasBiomeData.set(mapchunk.getHandle(), true); //yes, has biome data

		// =====================================
		// =========== Fill with data ==========
		// =====================================
		int chunkDataBitMap = 0;
		int chunkBiomeBitMap = 0;
		int i;

		// Calculate the available chunk sections and bitmap
		Object sections[] = ChunkRef.sections.invoke(chunk);
		boolean sectionsEmpty[] = new boolean[sections.length];
		for (i = 0; i < sections.length; i++) {
			sectionsEmpty[i] = sections[i] == null || ChunkSectionRef.isEmpty.invoke(sections[i]);
			if (!sectionsEmpty[i]) {
				chunkDataBitMap |= 1 << i;
				if (ChunkSectionRef.getExtBlockIds.invoke(sections[i]) != null) {
					chunkBiomeBitMap |= 1 << i;
				}
			}
		}

		// Check if it is an "EmptyChunk"
		// This type of packet needs separate handling
		if (chunkDataBitMap == 0 && chunkBiomeBitMap == 0) {
			// Fill with 0 data, 5 main sections of 2048 each
			this.rawLength = (5 * 2048);
			chunkDataBitMap = 1;
			java.util.Arrays.fill(this.rawbuffer, 0, this.rawLength, (byte) 0);
		} else {
			// Fill with chunk section data
			// There are 5 parts, each 2048 bytes of length
			this.rawLength = 0;
			for (i = 0; i < sections.length; i++) {
				if (!sectionsEmpty[i]) {
					rawAppend(ChunkSectionRef.getBlockIds.invoke(sections[i]));
				}
			}
			for (i = 0; i < sections.length; i++) {
				if (!sectionsEmpty[i]) {
					Object nibble = ChunkSectionRef.getBlockData.invoke(sections[i]);
					this.rawLength = NibbleArrayRef.copyTo(nibble, this.rawbuffer, this.rawLength);
				}
			}
			for (i = 0; i < sections.length; i++) {
				if (!sectionsEmpty[i]) {
					Object nibble  = ChunkSectionRef.getBlockLightNibble.invoke(sections[i]);
					this.rawLength = NibbleArrayRef.copyTo(nibble, this.rawbuffer, this.rawLength);
				}
			}
			
			//fix for 1.4.6 - start
			if(hasSkylight) {
				for (i = 0; i < sections.length; i++) {
					if (!sectionsEmpty[i]) {
						Object nibble = ChunkSectionRef.getSkyLightNibble.invoke(sections[i]);
						this.rawLength = NibbleArrayRef.copyTo(nibble, this.rawbuffer, this.rawLength);
					}
				}
			}
			//fix for 1.4.6 - end
			
			for (i = 0; i < sections.length; i++) {
				if (!sectionsEmpty[i] && ChunkSectionRef.getExtBlockIds.invoke(sections[i]) != null) {
					this.rawLength = NibbleArrayRef.copyTo(ChunkSectionRef.getExtBlockIds.invoke(sections[i]), this.rawbuffer, this.rawLength);
				}
			}
		}
		// Biome information
		rawAppend(ChunkRef.biomeData.invoke(chunk));
		// =====================================

		// Set data in packet
		mapchunk.write(PacketType.OUT_MAP_CHUNK.chunkDataBitMap, chunkDataBitMap);
		mapchunk.write(PacketType.OUT_MAP_CHUNK.chunkBiomeBitMap, chunkBiomeBitMap);
		mapchunk.write(PacketType.OUT_MAP_CHUNK.size, this.rawLength);
		mapchunk.write(PacketType.OUT_MAP_CHUNK.inflatedBuffer, this.rawbuffer);
		return mapchunk;
	}

	private void deflate(CommonPacket packet) {
		int size = PacketType.OUT_MAP_CHUNK.size.get(packet.getHandle());
		// Set input information
		this.deflater.reset();
		this.deflater.setLevel(6);
		this.deflater.setInput(PacketType.OUT_MAP_CHUNK.inflatedBuffer.get(packet.getHandle()), 0, size);
		this.deflater.finish();
		// Start deflating
		size = this.deflater.deflate(this.compbuffer);
		if (size == 0) {
			size = this.deflater.deflate(this.compbuffer);
		}
		byte[] buffer = new byte[size];
		System.arraycopy(this.compbuffer, 0, buffer, 0, size);
		// Write to packet
		PacketType.OUT_MAP_CHUNK.size.set(packet.getHandle(), size);
		PacketType.OUT_MAP_CHUNK.buffer.set(packet.getHandle(), buffer);
		PacketType.OUT_MAP_CHUNK.inflatedBuffer.set(packet.getHandle(), null); // dereference
	}

	public static double getBusyPercentage(long timescale) {
		double per = 0;
		if (threads != null && threads.length > 0) {
			for (ChunkCompressionThread thread : threads) {
				if (thread != null) {
					per += (double) thread.busyDuration / timescale;
					thread.busyDuration = 0;
				}
			}
			per /= threads.length;
		}
		return per;
	}

	public long busyDuration = 0;
	private long lasttime;

	/**
	 * Obtains the compressed chunk data packet for the given chunk
	 * 
	 * @param player to get the compressed packet for
	 * @param chunk to get the data for
	 * @return compressed packet of which the raw data can no longer be used
	 */
	public CommonPacket getCompressedPacket(Player player, org.bukkit.Chunk chunk) {
		this.lasttime = System.currentTimeMillis();
		CommonPacket mapchunk = createPacket(chunk);

		// send chunk through possible plugins
		// ========================================
		if (NoLaggChunks.isOreObfEnabled) {
			try {
				IPacket51 pack = InternalAccessor.Instance.newPacket51();
				pack.setPacket(mapchunk.getHandle());
				Calculations.Obfuscate(pack, player, false);
			} catch (Throwable t) {
				NoLaggChunks.plugin.log(Level.SEVERE, "An error occured in Orebfuscator: support for this plugin had to be removed!");
				t.printStackTrace();
				NoLaggChunks.isOreObfEnabled = false;
			}
		}
		// ========================================
		if (NoLaggChunks.isSpigotObfEnabled) {
			final int bitmap =  mapchunk.read(PacketType.OUT_MAP_CHUNK.chunkDataBitMap);
			final byte[] buffer = mapchunk.read(PacketType.OUT_MAP_CHUNK.inflatedBuffer);
			final Object worldHandle = Conversion.toWorldHandle.convert(chunk.getWorld());

			// Initialize the Spigot Anti-XRay support module
			if (spigotObfuscateMethod == null) {
				Class<?> orebfuscatorManagerClass = CommonUtil.getClass("org.spigotmc.OrebfuscatorManager");
				if (orebfuscatorManagerClass != null) {
					spigotObfuscateMethod = new SafeMethod<Void>(orebfuscatorManagerClass, "obfuscate", SPIGOT_OBFUSCATE_ARGS);
				} else {
					// Default back to the config-based anti-xray system
					final FieldAccessor<Object> spigotConfigField = new SafeField<Object>(worldHandle, "spigotConfig");
					if (spigotConfigField.isValid()) {
						Object spigotConfig = spigotConfigField.get(worldHandle);
						final FieldAccessor<Object> antiXrayField = new SafeField<Object>(spigotConfig, "antiXrayInstance");
						if (antiXrayField.isValid()) {
							// Now proceed to find the obfuscate() method
							Object antiXray = antiXrayField.get(spigotConfig);
							final MethodAccessor<Void> obfuscateMethod = new SafeMethod<Void>(antiXray, "obfuscate", SPIGOT_OBFUSCATE_ARGS);
							if (obfuscateMethod.isValid()) {
								// Translator method to take care of the instanced method
								spigotObfuscateMethod = new SafeDirectMethod<Void>() {
									@Override
									public Void invoke(Object instance, Object... args) {
										// Gather world from arguments
										Object worldHandle = args[4];
										Object spigotConfig = spigotConfigField.get(worldHandle);
										Object antiXray = antiXrayField.get(spigotConfig);
										return obfuscateMethod.invoke(antiXray, args);
									}
								};
							}
							
						}
					}
				}
			}

			// If enabled properly, go ahead and try to obfuscate
			if (spigotObfuscateMethod != null && spigotObfuscateMethod.isValid()) {
				try {
					spigotObfuscateMethod.invoke(null, chunk.getX(), chunk.getZ(), bitmap, buffer, worldHandle);
				} catch (Throwable t) {
					NoLaggChunks.isSpigotObfEnabled = false;
					NoLaggChunks.plugin.log(Level.SEVERE, "An error occurred while processing caused by Spigot Anti-Xray:");
					t.printStackTrace();
				}
			} else {
				NoLaggChunks.isSpigotObfEnabled = false;
				NoLaggChunks.plugin.log(Level.SEVERE, "Unable to provide support for Spigot Anti-Xray!");
			}
		}

		// compression
		this.deflate(mapchunk);
		// end of processing, return produced packet
		this.busyDuration += System.currentTimeMillis() - lasttime;
		return mapchunk;
	}

	@Override
	public void run() {
		try {
			ChunkCompressQueue queue = nextQueue();
			if (queue == null) {
				sleep(200);
				return;
			}
			org.bukkit.Chunk chunk = queue.pollChunk();
			if (chunk != null) {
				try {
					queue.enqueue(new ChunkSendCommand(this.getCompressedPacket(queue.owner(), chunk), chunk));
				} catch (Throwable t) {
					NoLaggChunks.plugin.log(Level.SEVERE, "Failed to compress map chunk [" + chunk.getX() + ", " + chunk.getZ() + "] for player " + queue.owner().getName());
					t.printStackTrace();
				}
			} else if (idleCounter++ > compress.size()) {
				sleep(100);
			} else {
				return;
			}
			idleCounter = 0;
		} catch (Throwable t) {
			t.printStackTrace();
		}
	}
}
