package nl.jdries.perfbridge;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class PerfBridge extends JavaPlugin implements Listener, CommandExecutor {

    // ── /sys paths ─────────────────────────────────────────────────────────────
    private static final Path TEMP_PATH  = Path.of("/sys/class/thermal/thermal_zone2/temp");
    private static final Path POWER_FILE  = Path.of("/home/container/power_w");  // written by host sidecar
    private static final String FREQ_GLOB = "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_cur_freq";
    private static final int NUM_CORES   = Runtime.getRuntime().availableProcessors();

    // ── State ──────────────────────────────────────────────────────────────────
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final BlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(4096);

    private volatile Path outFile;
    private volatile long stopAtTick = Long.MAX_VALUE;
    private volatile long tickCount  = 0;

    // Who triggered the current recording session (for auto-stop feedback)
    private volatile CommandSender recordingInitiator;

    // Async writer thread
    private Thread writerThread;
    private PrintWriter writer;

    private static final Path JSON_OUT = Path.of("/tmp/mc_perf.json");
    private static final Path JSON_TMP = Path.of("/tmp/mc_perf.json.tmp");
    private final java.util.ArrayDeque<Double> rollingTicks = new java.util.ArrayDeque<>();

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getCommand("perfmon").setExecutor(this);
        // Always write live JSON summary every second for the terminal monitor
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::writeLiveJson, 20L, 20L);
        getLogger().info("PerfBridge ready — use /perfmon start [seconds]");
    }

    private void writeLiveJson() {
        Double[] arr;
        synchronized (rollingTicks) { arr = rollingTicks.toArray(new Double[0]); }
        if (arr.length == 0) return;
        try {
            int w5s = Math.min(arr.length, 100), w10s = Math.min(arr.length, 200), w1m = arr.length;
            Files.writeString(JSON_TMP,
                "{\"5s\":" + windowJson(arr, w5s) +
                ",\"10s\":" + windowJson(arr, w10s) +
                ",\"1m\":" + windowJson(arr, w1m) +
                ",\"ts\":" + System.currentTimeMillis() + "}");
            Files.move(JSON_TMP, JSON_OUT, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    private String windowJson(Double[] arr, int size) {
        int from = Math.max(0, arr.length - size);
        double sum = 0, min = Double.MAX_VALUE, max = 0; int n = 0;
        for (int i = from; i < arr.length; i++) {
            double v = arr[i]; sum += v; if (v < min) min = v; if (v > max) max = v; n++;
        }
        double avg = n > 0 ? sum / n : 0;
        double tps = Math.min(20.0, avg > 0 ? 1000.0 / avg : 20.0);
        return String.format("{\"avg\":%.2f,\"min\":%.2f,\"max\":%.2f,\"tps\":%.2f}", avg, min, max, tps);
    }

    @Override
    public void onDisable() {
        stopRecording(null);
    }

    // ── Command ────────────────────────────────────────────────────────────────
    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            sender.sendMessage("Usage: /perfmon start [seconds] | /perfmon stop");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "start" -> {
                if (recording.get()) {
                    sender.sendMessage("[PerfBridge] Already recording. Use /perfmon stop first.");
                    return true;
                }
                int seconds = -1;
                if (args.length >= 2) {
                    try { seconds = Integer.parseInt(args[1]); } catch (NumberFormatException e) {
                        sender.sendMessage("[PerfBridge] Invalid duration: " + args[1]);
                        return true;
                    }
                }
                startRecording(sender, seconds);
            }
            case "stop" -> {
                if (!recording.get()) {
                    sender.sendMessage("[PerfBridge] Not currently recording.");
                    return true;
                }
                stopRecording(sender);
            }
            default -> sender.sendMessage("Usage: /perfmon start [seconds] | /perfmon stop");
        }
        return true;
    }

    // ── Recording lifecycle ────────────────────────────────────────────────────
    private void startRecording(CommandSender sender, int seconds) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        outFile = Path.of("/tmp/mc_perf_" + ts + ".csv");
        tickCount = 0;
        recordingInitiator = sender;

        if (seconds > 0) {
            stopAtTick = seconds * 20L;  // approx ticks
            sender.sendMessage("[PerfBridge] Recording for " + seconds + "s → " + outFile);
        } else {
            stopAtTick = Long.MAX_VALUE;
            sender.sendMessage("[PerfBridge] Recording started → " + outFile + "  (/perfmon stop to end)");
        }

        // Open writer on async thread
        writerThread = new Thread(() -> {
            try (PrintWriter pw = new PrintWriter(
                    new BufferedWriter(Files.newBufferedWriter(outFile,
                            StandardOpenOption.CREATE, StandardOpenOption.WRITE)))) {
                writer = pw;
                pw.println("tick,timestamp_ms,mspt,cpu_temp_c,cpu_freq_mhz,cpu_power_w");
                while (recording.get() || !writeQueue.isEmpty()) {
                    String line = writeQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (line != null) pw.println(line);
                }
                pw.flush();
            } catch (Exception e) {
                getLogger().warning("Writer error: " + e.getMessage());
            }
            writer = null;
        }, "perfbridge-writer");
        writerThread.setDaemon(true);

        recording.set(true);
        writerThread.start();
    }

    private void stopRecording(CommandSender sender) {
        if (!recording.compareAndSet(true, false)) return;
        stopAtTick = Long.MAX_VALUE;
        // Let the writer thread drain and exit
        if (writerThread != null) {
            try { writerThread.join(3000); } catch (InterruptedException ignored) {}
        }
        String msg = "[PerfBridge] Stopped. " + tickCount + " ticks written to " + outFile;
        getLogger().info(msg);
        if (sender != null) sender.sendMessage(msg);
    }

    // ── Tick event ─────────────────────────────────────────────────────────────
    @EventHandler
    public void onTickEnd(ServerTickEndEvent e) {
        double mspt = e.getTickDuration();
        synchronized (rollingTicks) {
            rollingTicks.addLast(mspt);
            if (rollingTicks.size() > 1200) rollingTicks.pollFirst();
        }

        if (!recording.get()) return;

        tickCount++;
        if (tickCount >= stopAtTick) {
            CommandSender initiator = recordingInitiator;
            getServer().getScheduler().runTask(this, () -> stopRecording(
                    initiator != null ? initiator : getServer().getConsoleSender()));
        }

        long   tsMs   = System.currentTimeMillis();
        double tempC  = readTemp();
        double freqMhz = readFreqMhz();
        double powerW = readPowerW();

        String line = tickCount + "," + tsMs + "," +
                      String.format("%.3f", mspt) + "," +
                      String.format("%.1f", tempC) + "," +
                      String.format("%.1f", freqMhz) + "," +
                      String.format("%.2f", powerW);

        writeQueue.offer(line);  // non-blocking; drop if queue full
    }

    // ── /sys readers ───────────────────────────────────────────────────────────
    private double readTemp() {
        try {
            return Long.parseLong(Files.readString(TEMP_PATH).trim()) / 1000.0;
        } catch (Exception e) { return -1; }
    }

    private double readFreqMhz() {
        long sum = 0; int count = 0;
        for (int i = 0; i < NUM_CORES; i++) {
            try {
                String v = Files.readString(Path.of(String.format(FREQ_GLOB, i))).trim();
                sum += Long.parseLong(v);
                count++;
            } catch (Exception ignored) {}
        }
        return count > 0 ? (sum / (double) count) / 1000.0 : -1;  // kHz → MHz
    }

    private double readPowerW() {
        try {
            return Double.parseDouble(Files.readString(POWER_FILE).trim());
        } catch (Exception e) { return -1; }
    }
}
