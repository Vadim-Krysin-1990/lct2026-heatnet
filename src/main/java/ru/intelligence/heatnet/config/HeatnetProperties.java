package ru.intelligence.heatnet.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "heatnet")
public class HeatnetProperties {
    /** Каталог хранения входных файлов, результатов и временных файлов. */
    private String storageDir = "/var/lib/heatnet";
    /** Число параллельных расчётов. */
    private int workers = 2;
    /** Каталог с переопределёнными правилами (reference.yml, restrictions.yml, routing.yml); пусто — classpath. */
    private String rulesDir = "";

    public String getStorageDir() { return storageDir; }
    public void setStorageDir(String storageDir) { this.storageDir = storageDir; }
    public int getWorkers() { return workers; }
    public void setWorkers(int workers) { this.workers = workers; }
    /** С какого размера входного файла включать двухпроходное чтение с фильтром по области. */
    private long areaScanThresholdBytes = 200L * 1024 * 1024;
    /** Запас вокруг области расчёта, градусы (0,02° ≈ 2,2 км по широте Москвы). */
    private double areaMarginDeg = 0.02;

    public long getAreaScanThresholdBytes() { return areaScanThresholdBytes; }
    public void setAreaScanThresholdBytes(long v) { this.areaScanThresholdBytes = v; }
    public double getAreaMarginDeg() { return areaMarginDeg; }
    public void setAreaMarginDeg(double v) { this.areaMarginDeg = v; }

    public String getRulesDir() { return rulesDir; }
    public void setRulesDir(String rulesDir) { this.rulesDir = rulesDir; }
}
