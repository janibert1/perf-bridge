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
import oshi.hardware.Sensors;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class PerfBridge extends JavaPlugin implements Listener, CommandExecutor {

    // ── OSHI handles ───────────────────────────────────────────────────────────
    private CentralProcessor cpu;
    private Sensors sensors;

    // ── State ──────────────────────────────────────────────────────────────────
    private final AtomicBoolean recording    = new AtomicBoolean(false);
    private final BlockingQueue<String> writeQueue = new ArrayBlockingQueue<>(8192);

    private volatile Path            outFile;
    private volatile long            stopAtTick   = Long.MAX_VALUE;
    private volatile long            tickCount    = 0;
    private volatile CommandSender   initiator;

    private Thread    writerThread;

    // ── Live JSON (for terminal monitor) ──────────────────────────────────────
    private static final Path JSON_OUT = Path.of("/tmp/mc_perf.json");
    private static final Path JSON_TMP = Path.of("/tmp/mc_perf.json.tmp");
    private final ArrayDeque<Double> rollingTicks = new ArrayDeque<>();

    // ── Optional power file (Linux/Intel RAPL sidecar) ────────────────────────
    private Path powerFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        String powerFilePath = getConfig().getString("power-file", "");
        powerFile = powerFilePath.isEmpty() ? null : Path.of(powerFilePath);

        try {
            SystemInfo si = new SystemInfo();
            HardwareAbstractionLayer hal = si.getHardware();
            cpu     = hal.getProcessor();
            sensors = hal.getSensors();
            getLogger().info("OSHI initialized — OS: " + System.getProperty("os.name")
                    + ", cores: " + cpu.getLogicalProcessorCount());
        } catch (Exception e) {
            getLogger().warning("OSHI init failed: " + e.getMessage() + " — hardware stats will show -1");
        }

        getServer().getPluginManager().registerEvents(this, this);
        getCommand("perfmon").setExecutor(this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, this::writeLiveJson, 20L, 20L);
        getLogger().info("PerfBridge ready — /perfmon start [seconds] | /perfmon stop");
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
                    sender.sendMessage("[PerfBridge] Already recording — /perfmon stop first.");
                    return true;
                }
                int seconds = -1;
                if (args.length >= 2) {
                    try { seconds = Integer.parseInt(args[1]); }
                    catch (NumberFormatException e) {
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
        outFile   = Path.of(getConfig().getString("output-dir", "/tmp"), "mc_perf_" + ts + ".csv");
        tickCount = 0;
        initiator = sender;

        String msg = seconds > 0
                ? "[PerfBridge] Recording for " + seconds + "s → " + outFile
                : "[PerfBridge] Recording started → " + outFile + "  (/perfmon stop to end)";
        sender.sendMessage(msg);
        stopAtTick = seconds > 0 ? seconds * 20L : Long.MAX_VALUE;

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
        double mspt = e.getTickDuration();
        synchronized (rollingTicks) {
            rollingTicks.addLast(mspt);
            if (rollingTicks.size() > 1200) rollingTicks.pollFirst();
        }

        if (!recording.get()) return;

        tickCount++;
        if (tickCount >= stopAtTick) {
            CommandSender s = initiator;
            getServer().getScheduler().runTask(this,
                    () -> stopRecording(s != null ? s : getServer().getConsoleSender()));
        }

        double tempC   = readTemp();
        double freqMhz = readFreqMhz();
        double powerW  = readPower();

        writeQueue.offer(tickCount + "," + System.currentTimeMillis() + ","
                + String.format("%.3f,%.1f,%.1f,%.2f", mspt, tempC, freqMhz, powerW));
    }

    // ── Hardware readers (OSHI) ────────────────────────────────────────────────
    private double readTemp() {
        try { return sensors.getCpuTemperature(); }
        catch (Exception e) { return -1; }
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
        if (powerFile == null) return -1;
        try { return Double.parseDouble(Files.readString(powerFile).trim()); }
        catch (Exception e) { return -1; }
    }

    // ── Live JSON summary ──────────────────────────────────────────────────────
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
        return String.format("{\"avg\":%.2f,\"min\":%.2f,\"max\":%.2f,\"tps\":%.2f}",
                avg, min, max, Math.min(20.0, avg > 0 ? 1000.0 / avg : 20.0));
    }
}
