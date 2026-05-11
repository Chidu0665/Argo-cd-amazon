package org.amazon;

import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;

/**
 * HealthCheckServlet
 *
 * Kubernetes probe endpoints:
 *   GET /health/live  → Liveness probe  (JVM alive?)
 *   GET /health/ready → Readiness probe (ready to serve?)
 *   GET /health       → Full health summary (Grafana/monitoring)
 *
 * Place at: Amazon-Core/src/main/java/org/amazon/HealthCheckServlet.java
 */
@WebServlet(urlPatterns = {"/health", "/health/live", "/health/ready"})
public class HealthCheckServlet extends HttpServlet {

    private static final long serialVersionUID = 1L;

    // Readiness flag — false until init() completes
    // Kubernetes readiness probe returns 503 until this is true
    private static volatile boolean isReady = false;

    private static final long START_TIME = System.currentTimeMillis();

    @Override
    public void init() throws ServletException {
        super.init();
        isReady = true;
        log("HealthCheckServlet initialized — app is READY");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        String path = req.getServletPath();
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        if ("/health/live".equals(path)) {
            handleLiveness(resp);
        } else if ("/health/ready".equals(path)) {
            handleReadiness(resp);
        } else {
            handleFullHealth(resp);
        }
    }

    // ── LIVENESS ──────────────────────────────────────────────────────────
    // Called by Kubernetes livenessProbe every 15s
    // 200 = alive  |  500 = kill and restart this container
    private void handleLiveness(HttpServletResponse resp) throws IOException {
        try {
            Runtime runtime     = Runtime.getRuntime();
            long freeMemory     = runtime.freeMemory();
            long totalMemory    = runtime.totalMemory();
            long maxMemory      = runtime.maxMemory();
            double memUsedPct   = ((double)(totalMemory - freeMemory) / maxMemory) * 100;

            if (memUsedPct > 95.0) {
                resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                writeJson(resp, "DOWN", "Memory critically high: " + String.format("%.1f", memUsedPct) + "%");
                return;
            }

            resp.setStatus(HttpServletResponse.SC_OK);
            writeJson(resp, "UP", "JVM alive. Memory used: " + String.format("%.1f", memUsedPct) + "%");

        } catch (Exception e) {
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            writeJson(resp, "DOWN", "Liveness check failed: " + e.getMessage());
        }
    }

    // ── READINESS ─────────────────────────────────────────────────────────
    // Called by Kubernetes readinessProbe every 10s
    // 200 = send traffic here  |  503 = remove from LoadBalancer (no restart)
    private void handleReadiness(HttpServletResponse resp) throws IOException {
        if (isReady) {
            resp.setStatus(HttpServletResponse.SC_OK);
            writeJson(resp, "UP", "Application is ready to serve traffic");
        } else {
            resp.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            writeJson(resp, "OUT_OF_SERVICE", "Application is not yet ready");
        }
    }

    // ── FULL HEALTH (for Grafana dashboards) ──────────────────────────────
    private void handleFullHealth(HttpServletResponse resp) throws IOException {
        Runtime runtime          = Runtime.getRuntime();
        MemoryMXBean memBean     = ManagementFactory.getMemoryMXBean();
        OperatingSystemMXBean os = ManagementFactory.getOperatingSystemMXBean();

        long uptimeSec = (System.currentTimeMillis() - START_TIME) / 1000;
        long heapUsed  = memBean.getHeapMemoryUsage().getUsed()   / (1024 * 1024);
        long heapMax   = memBean.getHeapMemoryUsage().getMax()    / (1024 * 1024);
        long nonHeap   = memBean.getNonHeapMemoryUsage().getUsed()/ (1024 * 1024);

        resp.setStatus(isReady ? HttpServletResponse.SC_OK : HttpServletResponse.SC_SERVICE_UNAVAILABLE);

        PrintWriter out = resp.getWriter();
        out.println("{");
        out.println("  \"status\": \""          + (isReady ? "UP" : "DOWN") + "\",");
        out.println("  \"components\": {");
        out.println("    \"liveness\":  { \"status\": \"UP\" },");
        out.println("    \"readiness\": { \"status\": \"" + (isReady ? "UP" : "OUT_OF_SERVICE") + "\" }");
        out.println("  },");
        out.println("  \"details\": {");
        out.println("    \"uptime_seconds\": "          + uptimeSec + ",");
        out.println("    \"heap_used_mb\": "            + heapUsed  + ",");
        out.println("    \"heap_max_mb\": "             + heapMax   + ",");
        out.println("    \"non_heap_mb\": "             + nonHeap   + ",");
        out.println("    \"available_processors\": "    + runtime.availableProcessors() + ",");
        out.println("    \"system_load\": "             + String.format("%.2f", os.getSystemLoadAverage()));
        out.println("  }");
        out.println("}");
    }

    private void writeJson(HttpServletResponse resp, String status, String message)
            throws IOException {
        PrintWriter out = resp.getWriter();
        out.println("{");
        out.println("  \"status\": \""  + status  + "\",");
        out.println("  \"message\": \"" + message + "\"");
        out.println("}");
    }

    // Called during graceful shutdown — marks app as not ready
    @Override
    public void destroy() {
        isReady = false;
        log("HealthCheckServlet destroyed — app marked NOT READY");
        super.destroy();
    }
}