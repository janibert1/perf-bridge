package nl.jdries.perfbridge;

import com.destroystokyo.paper.event.server.ServerTickEndEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.hardware.PowerSource;
import oshi.hardware.Sensors;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Locale;

public class PerfBridge extends JavaPlugin implements Listener, CommandExecutor {

    // ── OSHI handles ───────────────────────────────────────────────────────────
    private CentralProcessor cpu;
    private Sensors sensors;
    private HardwareAbstractionLayer hal;

    // ── CSV recording state ────────────────────────────────────────────────────
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final BlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(8192);
    private volatile Path outFile;
    private volatile long stopAtTick = Long.MAX_VALUE;
    private volatile long tickCount  = 0;
    private volatile CommandSender initiator;
    private Thread writerThread;

    // ── Live JSON ──────────────────────────────────────────────────────────────
    private static final Path JSON_OUT = Path.of("/tmp/mc_perf.json");
    private static final Path JSON_TMP = Path.of("/tmp/mc_perf.json.tmp");
    private final ArrayDeque<Double> rollingTicks = new ArrayDeque<>();

    // ── Optional power sidecar file ────────────────────────────────────────────
    private Path powerFile;

    // ── Spike profiler ─────────────────────────────────────────────────────────
    private static final class Sample {
        final long nanoTime;
        final StackTraceElement[] stack;
        Sample(long nanoTime, StackTraceElement[] stack) { this.nanoTime = nanoTime; this.stack = stack; }
    }

    private static final int BUFFER_SIZE = 4096; // power of 2 for fast masking
    private final Sample[] sampleBuffer  = new Sample[BUFFER_SIZE];
    private final AtomicInteger sampleHead = new AtomicInteger(0);
    private volatile Thread mainThread;
    private Thread samplerThread;
    private volatile boolean samplerRunning = false;
    private double spikeThresholdMs;
    private Path spikeDir;

    // ── Lifecycle ──────────────────────────────────────────────────────────────
    @Override
    public void onEnable() {
        saveDefaultConfig();

        String powerFilePath = getConfig().getString("power-file", "");
        powerFile = powerFilePath.isEmpty() ? null : Path.of(powerFilePath);

        spikeThresholdMs = getConfig().getDouble("spike-threshold-ms", 40.0);
        spikeDir = Path.of(getConfig().getString("output-dir", "/tmp"));

        try {
            SystemInfo si = new SystemInfo();
            hal     = si.getHardware();
            cpu     = hal.getProcessor();
            sensors = hal.getSensors();
            getLogger().info("OSHI initialized — OS: " + System.getProperty("os.name")
                    + ", cores: " + cpu.getLogicalProcessorCount());
        } catch (Exception e) {
            getLogger().warning("OSHI init failed: " + e.getMessage());
        }

        startSampler();

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("perfmon").setExecutor(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::writeLiveJson, 20L, 20L);
        getLogger().info("PerfBridge ready — spike profiler active (threshold: " + spikeThresholdMs + "ms)");
    }

    @Override
    public void onDisable() {
        samplerRunning = false;
        if (samplerThread != null) samplerThread.interrupt();
        stopRecording(null);
    }

    // ── Spike profiler sampler ─────────────────────────────────────────────────
    private void startSampler() {
        samplerRunning = true;
        samplerThread = new Thread(() -> {
            while (samplerRunning) {
                Thread target = mainThread;
                if (target != null) {
                    StackTraceElement[] stack = target.getStackTrace();
                    int idx = sampleHead.getAndIncrement() & (BUFFER_SIZE - 1);
                    sampleBuffer[idx] = new Sample(System.nanoTime(), stack);
                }
                try { Thread.sleep(1); } catch (InterruptedException e) { break; }
            }
        }, "perfbridge-sampler");
        samplerThread.setDaemon(true);
        samplerThread.start();
    }

    private void handleSpike(double mspt, long tickEndNs) {
        long tickStartNs = tickEndNs - (long) (mspt * 1_000_000.0);
        Sample[] snapshot = sampleBuffer.clone();
        int head = sampleHead.get();
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        Path out = spikeDir.resolve("spike_" + ts + "_" + (int) mspt + "ms.txt");

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            // Collect samples that fall within the spike window
            List<StackTraceElement[]> spikeSamples = new ArrayList<>();
            for (int i = 0; i < BUFFER_SIZE; i++) {
                Sample s = snapshot[(head - BUFFER_SIZE + i) & (BUFFER_SIZE - 1)];
                if (s != null && s.nanoTime >= tickStartNs && s.nanoTime <= tickEndNs) {
                    spikeSamples.add(s.stack);
                }
            }
            if (spikeSamples.isEmpty()) return;
            writeSpikeReport(out, mspt, spikeSamples);
        });
    }

    private void writeSpikeReport(Path out, double mspt, List<StackTraceElement[]> samples) {
        int total = samples.size();

        // Frame frequency — how many samples each frame appears in
        Map<String, Integer> frameCount = new LinkedHashMap<>();
        for (StackTraceElement[] stack : samples) {
            Set<String> seen = new HashSet<>();
            for (StackTraceElement frame : stack) {
                String key = frame.toString();
                if (seen.add(key)) frameCount.merge(key, 1, Integer::sum);
            }
        }

        // Sort frames by frequency
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(frameCount.entrySet());
        sorted.sort((a, b) -> b.getValue() - a.getValue());

        // Aggregate unique call stacks by signature (top 6 frames)
        Map<String, int[]> stackGroups = new LinkedHashMap<>();
        for (StackTraceElement[] stack : samples) {
            StringBuilder sig = new StringBuilder();
            int depth = Math.min(stack.length, 6);
            for (int i = 0; i < depth; i++) sig.append(stack[i]).append('\n');
            stackGroups.computeIfAbsent(sig.toString(), k -> new int[1])[0]++;
        }
        List<Map.Entry<String, int[]>> topStacks = new ArrayList<>(stackGroups.entrySet());
        topStacks.sort((a, b) -> b.getValue()[0] - a.getValue()[0]);

        try (PrintWriter pw = new PrintWriter(Files.newBufferedWriter(out,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE))) {
            pw.printf("=== SPIKE: %.1fms | %s | %d samples ===%n",
                    mspt, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")), total);
            pw.println();

            pw.println("HOT FRAMES (% of spike):");
            int limit = Math.min(sorted.size(), 20);
            for (int i = 0; i < limit; i++) {
                Map.Entry<String, Integer> e = sorted.get(i);
                int pct = (int) Math.round(e.getValue() * 100.0 / total);
                if (pct < 5) break;
                pw.printf("  %3d%%  %s%n", pct, e.getKey());
            }

            pw.println();
            pw.println("TOP CALL STACKS:");
            int stackLimit = Math.min(topStacks.size(), 5);
            for (int i = 0; i < stackLimit; i++) {
                Map.Entry<String, int[]> e = topStacks.get(i);
                int pct = (int) Math.round(e.getValue()[0] * 100.0 / total);
                pw.printf("[%d samples / %d%%]%n", e.getValue()[0], pct);
                for (String line : e.getKey().split("\n")) pw.println("  " + line);
                pw.println();
            }
        } catch (IOException e) {
            getLogger().warning("Failed to write spike report: " + e.getMessage());
        }

        getLogger().info("[PerfBridge] Spike report: " + out.getFileName());
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
                if (recording.get()) { sender.sendMessage("[PerfBridge] Already recording."); return true; }
                int seconds = -1;
                if (args.length >= 2) {
                    try { seconds = Integer.parseInt(args[1]); }
                    catch (NumberFormatException e) {
                        sender.sendMessage("[PerfBridge] Invalid duration: " + args[1]); return true;
                    }
                }
                startRecording(sender, seconds);
            }
            case "stop" -> {
                if (!recording.get()) { sender.sendMessage("[PerfBridge] Not recording."); return true; }
                stopRecording(sender);
            }
            default -> sender.sendMessage("Usage: /perfmon start [seconds] | /perfmon stop");
        }
        return true;
    }

    // ── CSV recording ──────────────────────────────────────────────────────────
    private void startRecording(CommandSender sender, int seconds) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
        outFile   = Path.of(getConfig().getString("output-dir", "/tmp"), "mc_perf_" + ts + ".csv");
        tickCount = 0;
        initiator = sender;
        stopAtTick = seconds > 0 ? seconds * 20L : Long.MAX_VALUE;

        String msg = seconds > 0
                ? "[PerfBridge] Recording for " + seconds + "s → " + outFile
                : "[PerfBridge] Recording started → " + outFile + "  (/perfmon stop to end)";
        sender.sendMessage(msg);

        writerThread = new Thread(() -> {
            try (PrintWriter pw = new PrintWriter(new BufferedWriter(
                    Files.newBufferedWriter(outFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)))) {
                pw.println("tick,timestamp_ms,mspt,cpu_temp_c,cpu_freq_mhz,cpu_power_w");
                while (recording.get() || !writeQueue.isEmpty()) {
                    String line = writeQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (line != null) pw.println(line);
                }
                pw.flush();
            } catch (Exception e) {
                getLogger().warning("Writer error: " + e.getMessage());
            }
        }, "perfbridge-writer");
        writerThread.setDaemon(true);
        recording.set(true);
        writerThread.start();
    }

    private void stopRecording(CommandSender sender) {
        if (!recording.compareAndSet(true, false)) return;
        stopAtTick = Long.MAX_VALUE;
        if (writerThread != null) {
            try { writerThread.join(3000); } catch (InterruptedException ignored) {}
        }
        String msg = "[PerfBridge] Done — " + tickCount + " ticks → " + outFile;
        getLogger().info(msg);
        if (sender != null) sender.sendMessage(msg);
    }

    // ── Tick event ─────────────────────────────────────────────────────────────
    @EventHandler
    public void onTickEnd(ServerTickEndEvent e) {
        if (mainThread == null) mainThread = Thread.currentThread();

        double mspt = e.getTickDuration();
        long nowNs  = System.nanoTime();

        synchronized (rollingTicks) {
            rollingTicks.addLast(mspt);
            if (rollingTicks.size() > 1200) rollingTicks.pollFirst();
        }

        if (mspt >= spikeThresholdMs) handleSpike(mspt, nowNs);

        if (!recording.get()) return;
        tickCount++;
        if (tickCount >= stopAtTick) {
            CommandSender s = initiator;
            getServer().getScheduler().runTask(this,
                    () -> stopRecording(s != null ? s : getServer().getConsoleSender()));
        }

        writeQueue.offer(tickCount + "," + System.currentTimeMillis() + ","
                + String.format(Locale.US, "%.3f,%.1f,%.1f,%.2f",
                        mspt, readTemp(), readFreqMhz(), readPower()));
    }

    // ── Hardware readers ───────────────────────────────────────────────────────
    private double readTemp() {
        try { return sensors.getCpuTemperature(); } catch (Exception e) { return -1; }
    }

    private double readFreqMhz() {
        try {
            long[] freqs = cpu.getCurrentFreq();
            if (freqs == null || freqs.length == 0) return -1;
            long sum = 0; int n = 0;
            for (long f : freqs) { if (f > 0) { sum += f; n++; } }
            return n > 0 ? (sum / (double) n) / 1_000_000.0 : -1;
        } catch (Exception e) { return -1; }
    }

    private double readPower() {
        if (powerFile != null) {
            try { return Double.parseDouble(Files.readString(powerFile).trim()); }
            catch (Exception e) { return -1; }
        }
        try {
            if (hal == null) return -1;
            List<PowerSource> sources = hal.getPowerSources();
            double total = 0; int count = 0;
            for (PowerSource ps : sources) {
                ps.updateAttributes();
                double rate = ps.getPowerUsageRate();
                if (Double.isNaN(rate) || rate == 0) continue;
                total += Math.abs(rate); count++;
            }
            return count > 0 ? total : -1;
        } catch (Exception e) { return -1; }
    }

    // ── Live JSON ──────────────────────────────────────────────────────────────
    private void writeLiveJson() {
        Double[] arr;
        synchronized (rollingTicks) { arr = rollingTicks.toArray(new Double[0]); }
        if (arr.length == 0) return;
        try {
            int w5s = Math.min(arr.length, 100), w10s = Math.min(arr.length, 200);
            Files.writeString(JSON_TMP,
                "{\"5s\":" + windowJson(arr, w5s) +
                ",\"10s\":" + windowJson(arr, w10s) +
                ",\"1m\":" + windowJson(arr, arr.length) +
                ",\"ts\":" + System.currentTimeMillis() + "}");
            Files.move(JSON_TMP, JSON_OUT, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {}
    }

    private String windowJson(Double[] arr, int size) {
        int from = Math.max(0, arr.length - size);
        double sum = 0, min = Double.MAX_VALUE, max = 0; int n = 0;
        for (int i = from; i < arr.length; i++) {
            double v = arr[i]; sum += v;
            if (v < min) min = v; if (v > max) max = v; n++;
        }
        double avg = n > 0 ? sum / n : 0;
        return String.format(Locale.US, "{\"avg\":%.2f,\"min\":%.2f,\"max\":%.2f,\"tps\":%.2f}",
                avg, min, max, Math.min(20.0, avg > 0 ? 1000.0 / avg : 20.0));
    }
}
