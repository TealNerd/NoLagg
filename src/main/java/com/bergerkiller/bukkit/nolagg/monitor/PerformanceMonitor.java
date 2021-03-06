package com.bergerkiller.bukkit.nolagg.monitor;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;

import com.bergerkiller.bukkit.common.MessageBuilder;
import com.bergerkiller.bukkit.common.StopWatch;
import com.bergerkiller.bukkit.common.Task;
import com.bergerkiller.bukkit.common.utils.CommonUtil;
import com.bergerkiller.bukkit.common.utils.EntityUtil;
import com.bergerkiller.bukkit.common.utils.StringUtil;
import com.bergerkiller.bukkit.common.utils.WorldUtil;
import com.bergerkiller.bukkit.common.utils.MathUtil;
import com.bergerkiller.bukkit.nolagg.NoLagg;
import com.bergerkiller.bukkit.nolagg.NoLaggComponents;
import com.bergerkiller.bukkit.nolagg.NoLaggUtil;
import com.bergerkiller.bukkit.nolagg.Permission;
import com.bergerkiller.bukkit.nolagg.chunks.ChunkSendQueue;
import com.bergerkiller.bukkit.nolagg.chunks.DynamicViewDistance;
import com.bergerkiller.bukkit.nolagg.lighting.LightingService;
import com.bergerkiller.bukkit.nolagg.tnt.NoLaggTNT;

public class PerformanceMonitor extends Task {

	public PerformanceMonitor() {
		super(NoLagg.plugin);
	}

	public static int monitorInterval = 40;

	public static long prevtime;
	public static long prevusedmem;
	public static long minmem;

	private static long prevlaggingmsg = System.currentTimeMillis();
	public static boolean broadcastLagging;
	public static String broadcastMessage;
	public static long broadcastInterval;
	public static double broadcastThreshold;

	private static final double strength = 0.7;

	public static ArrayList<String> recipients = new ArrayList<String>();
	public static HashSet<String> removalReq = new HashSet<String>();
	public static boolean sendConsole = false;
	public static boolean removalCon = false;
	public static boolean sendLog = false;
	private static final Runtime runtime = Runtime.getRuntime();
	private static PerformanceMonitor pm;
	private static BufferedWriter logger;
	private static boolean wroteHeader = false;

	private static File logfile;

	public static void init() {
		pm = new PerformanceMonitor();
		prevtime = System.currentTimeMillis();
		prevusedmem = runtime.maxMemory() - runtime.freeMemory();
		minmem = prevusedmem;
		pm.start(monitorInterval, monitorInterval);
		// set up logger
		logfile = NoLagg.plugin.getDataFolder();
		logfile.mkdirs();
		logfile = new File(logfile + File.separator + "log.txt");
		try {
			logger = new BufferedWriter(new FileWriter(logfile, true));
			log("NoLagg enabled: " + getStamp());
			logger.flush();
		} catch (IOException ex) {
			NoLaggMonitor.plugin.log(Level.SEVERE, "Failed to initialize performance logger file stream:");
			ex.printStackTrace();
		}
	}

	public static void deinit() {
		Task.stop(pm);
		pm = null;

		// set up logger
		if (logger != null) {
			try {
				log("NoLagg disabled: " + getStamp());
				logger.flush();
				logger.close();
			} catch (IOException ex) {
			}
			logger = null;
		}
		recipients.clear();
		removalReq.clear();
	}

	private static String getProgress(int length, ChatColor color) {
		if (length <= 0)
			return "";
		StringBuilder sb = new StringBuilder(length + 1);
		sb.append(color);
		for (int i = 0; i < length; i++) {
			sb.append('|');
		}
		return sb.toString();
	}

	private static String getMemoryProgress(int length, long current, long min, long max) {
		double factor = (double) length / max;
		StringBuilder sb = new StringBuilder(length + 3);
		int used = (int) (factor * min);
		int unused = (int) (factor * (max - current));
		sb.append(getProgress(used, ChatColor.GREEN));
		sb.append(getProgress(length - unused - used, ChatColor.YELLOW));
		sb.append(getProgress(unused, ChatColor.RED));
		return sb.toString();
	}

	private static int mem(double value) {
		return mem((long) value);
	}

	private static int mem(long value) {
		return (int) (value / 1048576);
	}

	public static boolean clearLog() {
		if (logger != null) {
			try {
				logger.close();
			} catch (Exception ex) {
				return false;
			}
		}
		if (logfile.delete()) {
			try {
				logger = new BufferedWriter(new FileWriter(logfile, true));
				wroteHeader = false;
				return true;
			} catch (Exception ex) {
				return false;
			}
		} else {
			return false;
		}
	}

	private static void log(String message) throws IOException {
		logger.write(message);
		logger.newLine();
	}

	public static boolean writeLog(String message) {
		try {
			log(message);
			return true;
		} catch (IOException ex) {
			return false;
		}
	}

	private static String getTime() {
		final SimpleDateFormat sdf = new SimpleDateFormat("H:mm:ss");
		return sdf.format(Calendar.getInstance().getTime());
	}

	private static String getStamp() {
		final SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd H:mm:ss");
		return sdf.format(Calendar.getInstance().getTime());
	}

	private static void appendColumn(StringBuilder builder, String text) {
		builder.append(" ").append(text).append("		|");
	}

	public static class ProcessTime extends StopWatch {
		public ProcessTime(String name, ChatColor color) {
			this.name = name;
			this.color = color;
		}

		public StopWatch stop() {
			return super.stop(strength);
		}

		public StopWatch next() {
			return super.next(strength);
		}

		public String name;
		public ChatColor color;
		public int barlength = 0;

		public String toString() {
			return this.color + "[" + this.name + "]";
		}
	}

	public static String getMemory(boolean player) {
		StringBuilder builder = new StringBuilder();
		if (player) {
			builder.append(ChatColor.YELLOW).append("Memory: ");
			builder.append(getMemoryProgress(50, usedmem, minmem, maxmem));
			builder.append(ChatColor.YELLOW).append(" ").append(mem(minmem)).append("/").append(mem(maxmem)).append(" MB ");
			if (diff >= 0) {
				builder.append(ChatColor.RED).append("(+").append(mem(diff / elapsedtimesec)).append(" MB/s)");
			} else {
				builder.append(ChatColor.GREEN).append("(GC)");
			}
		} else {
			builder.append("Memory: ").append(mem(minmem)).append("/").append(mem(maxmem)).append(" MB");
			builder.append(" (+").append(mem(usedmem - minmem)).append(" modified)");
			if (diff >= 0) {
				builder.append(" (+").append(mem(diff / elapsedtimesec)).append(" MB/s)");
			} else {
				builder.append(" (Garbage Collected)");
			}
		}
		return builder.toString();
	}

	public static String getTPS(boolean player) {
		StringBuilder builder = new StringBuilder();
		builder.append(ChatColor.YELLOW).append("Ticks per second: ");
		if (player) {
			if (tps >= 17 && tps <= 23) {
				builder.append(ChatColor.GREEN);
			} else if (tps >= 14 && tps <= 26) {
				builder.append(ChatColor.GOLD);
			} else {
				builder.append(ChatColor.RED);
			}
		}
		builder.append(MathUtil.round(tps, 1)).append(" [");
		builder.append(MathUtil.round(tps * 5, 0));
		builder.append("%]");
		return builder.toString();
	}

	public static long maxmem;
	public static long usedmem;
	public static double elapsedtimesec;
	public static long elapsedtime;
	public static long diff;
	public static double tps = 0;

	@Override
	public void run() {
		long time = System.currentTimeMillis();
		elapsedtime = time - prevtime;
		elapsedtimesec = (double) elapsedtime / 1000;
		maxmem = runtime.maxMemory();
		usedmem = runtime.totalMemory() - runtime.freeMemory();
		diff = usedmem - prevusedmem;
		if (diff < 0) {
			minmem = usedmem;
		}
		tps = monitorInterval / elapsedtimesec;
		if (tps < broadcastThreshold && broadcastLagging) {
			if (System.currentTimeMillis() > (prevlaggingmsg + broadcastInterval)) {
				prevlaggingmsg = System.currentTimeMillis();
				String msg = StringUtil.ampToColor(broadcastMessage);
				for (Player player : Bukkit.getServer().getOnlinePlayers()) {
					if (Permission.MONITOR_NOTIFYLAGGING.has(player)) {
						player.sendMessage(msg);
					}
				}
			}
		}
		double chunksendbusy = 0.0;
		if (NoLaggComponents.CHUNKS.isEnabled()) {
			chunksendbusy = MathUtil.round(ChunkSendQueue.compressBusyPercentage, 2);
		}
		if (sendLog || sendConsole || recipients.size() > 0) {
			int mobcount = 0;
			int entitycount = 0;
			int itemcount = 0;
			int tntcount = 0;
			int bufftnt = NoLaggComponents.TNT.isEnabled() ? NoLaggTNT.plugin.getTNTHandler().getBufferCount() : 0;
			int playercount = 0;
			for (World w : Bukkit.getServer().getWorlds()) {
				for (Entity e : w.getEntities()) {
					if (e.isDead())
						continue;
					if (e instanceof Player && !NoLaggUtil.isNPCPlayer((Player) e)) {
						playercount++;
					} else if (EntityUtil.isMob(e)) {
						mobcount++;
					} else if (e instanceof Item) {
						itemcount++;
					} else if (e instanceof TNTPrimed) {
						tntcount++;
					}
					entitycount++;
				}
			}
			int lighting;
			boolean isLightingActive = false;
			if (NoLaggComponents.LIGHTING.isEnabled()) {
				isLightingActive = LightingService.isProcessing();
				lighting = LightingService.getChunkFaults();
			} else {
				lighting = 0;
			}

			int totalchunkcount = 0;
			int totaluchunkcount = 0;

			for (World world : WorldUtil.getWorlds()) {
				int count = WorldUtil.getChunks(world).size();
				totalchunkcount += count;
				totaluchunkcount += count;
				if (world.getKeepSpawnInMemory()) {
					totaluchunkcount -= 144;
				}
			}
			// ================
			if (sendLog && logger != null) {
				try {
					if (!wroteHeader) {
						wroteHeader = true;
						StringBuilder columns = new StringBuilder();
						columns.append("| Time		| Tick rate		| Total Memory	| Static Memory	| Dynamic Memory	");
						columns.append("| Memory Write Rate	| Total Chunks	| Unloadable Chunks	");
						columns.append("| Chunks Loaded	| Chunks generated	| Chunks Unloaded	| Lighting Fixes	");
						columns.append("| Chunk Packets	| Entities		| Mobs		| Items		| TNT		| Players		| Update		| Update type	|");
						log(columns.toString());
					}
					StringBuilder msg = new StringBuilder(500);
					msg.append("|");
					appendColumn(msg, getTime());
					appendColumn(msg, String.valueOf(MathUtil.round(tps, 1)));
					appendColumn(msg, mem(maxmem) + " MB");
					appendColumn(msg, mem(minmem) + " MB");
					appendColumn(msg, mem(usedmem - minmem) + " MB");
					if (diff < 0) {
						appendColumn(msg, "GC");
					} else {
						appendColumn(msg, mem(diff / elapsedtimesec) + " MB/s");
					}
					appendColumn(msg, String.valueOf(totalchunkcount));
					appendColumn(msg, String.valueOf(totaluchunkcount));
					appendColumn(msg, String.valueOf(NLMListener.loadedChunks));
					appendColumn(msg, String.valueOf(NLMListener.generatedChunks));
					appendColumn(msg, String.valueOf(NLMListener.unloadedChunks));
					appendColumn(msg, String.valueOf(lighting));
					appendColumn(msg, String.valueOf(bufftnt));
					appendColumn(msg, String.valueOf(entitycount));
					appendColumn(msg, String.valueOf(mobcount));
					appendColumn(msg, String.valueOf(itemcount));
					appendColumn(msg, String.valueOf(tntcount));
					appendColumn(msg, String.valueOf(playercount));
					log(msg.toString());
					logger.flush();
				} catch (IOException ex) {
					NoLaggMonitor.plugin.log(Level.SEVERE, "Logging disabled:");
					ex.printStackTrace();
					try {
						logger.close();
					} catch (IOException e) {
					}
					logger = null;
				}
			}

			if (sendConsole) {
				CommandSender s = Bukkit.getServer().getConsoleSender();
				// Line
				s.sendMessage("-");
				StringBuilder msg = new StringBuilder();
				// memory
				s.sendMessage(getMemory(false));
				// chunks
				msg.append("Chunks: ");
				msg.append(totalchunkcount).append(" [").append(totaluchunkcount).append(" Unloadable]");
				msg.append(" [+").append((NLMListener.loadedChunks + NLMListener.generatedChunks)).append("]");
				msg.append(" [-").append(NLMListener.unloadedChunks).append("]");
				msg.append(" [").append(lighting).append(" lighting]");
				s.sendMessage(msg.toString());
				// Entities
				msg.setLength(0);
				msg.append("Entities: ").append(entitycount).append(" [").append(mobcount).append(" mobs]");
				msg.append(" [").append(itemcount).append(" items] [").append(tntcount).append(" mobile TNT]");
				s.sendMessage(msg.toString());
				// Compression thread busy
				msg.setLength(0);
				msg.append("Chunk packet sending thread: ").append(chunksendbusy).append("% busy");
				s.sendMessage(msg.toString());
				// Tick times
				s.sendMessage(getTPS(false));

				if (removalCon) {
					removalCon = false;
					sendConsole = false;
				}
			}
			if (recipients.size() > 0) {
				// Line
				MessageBuilder mem = new MessageBuilder();
				// Newline to flush the text before
				mem.newLine();

				// Memory
				mem.append(getMemory(true)).newLine();
				// Tick times
				mem.append(getTPS(true)).newLine();
				// Chunks
				mem.yellow("Chunks: ").gold(totalchunkcount, " [", totaluchunkcount, " U]");
				mem.green(" [+", NLMListener.loadedChunks, "]");
				mem.yellow(" [+", NLMListener.generatedChunks, "]");
				mem.red(" [-", NLMListener.unloadedChunks, "]");
				mem.append(isLightingActive ? ChatColor.RED : ChatColor.GOLD, " [", lighting, " lighting]");
				mem.newLine();
				// Buffering entities
				mem.yellow("Entities: ").gold(entitycount, " ").dark_green("[", mobcount, " mobs]");
				mem.yellow(" [", itemcount, " items] ").green("[", tntcount, " TNT] ").aqua("[", playercount, " players]");
				mem.newLine();
				// Compression thread busy
				mem.yellow("Packet compression busy: ");
				if (chunksendbusy > 60) {
					mem.append(ChatColor.RED);
				} else if (chunksendbusy > 30) {
					mem.append(ChatColor.RED);
				} else {
					mem.append(ChatColor.RED);
				}
				mem.append(chunksendbusy, "% busy");

				// average send rate
				double avgrate = 0;
				if (NoLaggComponents.CHUNKS.isEnabled()) {
					avgrate = ChunkSendQueue.getAverageRate();
				}

				int i = 0;
				while (i < recipients.size()) {
					String name = recipients.get(i);
					Player p = Bukkit.getServer().getPlayer(name);
					if (p != null) {
						mem.send(p);
						// send individual sending rate
						if (NoLaggComponents.CHUNKS.isEnabled()) {
							ChunkSendQueue queue = ChunkSendQueue.bind(p);
							int tosend = CommonUtil.CHUNKAREA - queue.getPendingSize();
							String msg = ChatColor.YELLOW + "Chunk sending: " + ChatColor.GREEN + (tosend * 100 / CommonUtil.CHUNKAREA) + "%";
							msg += ChatColor.YELLOW + " at " + ChatColor.GREEN + MathUtil.round(queue.getRate(), 2) + " chunks/tick (" + MathUtil.round(avgrate, 2) + " avg)";
							p.sendMessage(msg);
							msg = ChatColor.YELLOW + "Packet buffer size: " + queue.getBufferLoadMsg();
							p.sendMessage(msg);
							msg = ChatColor.YELLOW + "Dynamic view distance: " + ChatColor.GREEN + DynamicViewDistance.getViewDistance(p) + ChatColor.YELLOW + " chunks";
							p.sendMessage(msg);
						}
						if (removalReq.remove(name)) {
							recipients.remove(i);
						} else {
							i++;
						}
					} else {
						removalReq.remove(name);
						recipients.remove(i);
					}

				}
			}
		}

		NLMListener.reset();
		prevtime = time;
		prevusedmem = usedmem;
	}
}
