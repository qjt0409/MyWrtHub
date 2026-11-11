package com.whykangkang.wrthub.model;

/**
 * 设备限速全局设置,对应固件里的 luci-app-eqosplus(全局段 type=eqosplus)。
 *
 * download/upload 是 WAN 总带宽,eqosplus 的单位是 MB/s(可带小数),
 * 所以用字符串保存,避免 0.1 这类值被整数化。ifname 是限速接口,
 * 固件默认 "1" = 自动。
 */
public class EqosGlobal {

    public static final String DEFAULT_DOWNLOAD = "1000";
    public static final String DEFAULT_UPLOAD = "30";
    public static final String DEFAULT_IFNAME = "1";

    /** uci 段名(匿名段如 cfg019463);读到才有,写回时优先按段名定位 */
    public String section = "";
    public boolean enabled;
    public String download;
    public String upload;
    public String ifname;

    public EqosGlobal(boolean enabled, String download, String upload, String ifname) {
        this.enabled = enabled;
        this.download = download;
        this.upload = upload;
        this.ifname = ifname;
    }

    public static EqosGlobal defaults() {
        return new EqosGlobal(false, DEFAULT_DOWNLOAD, DEFAULT_UPLOAD, DEFAULT_IFNAME);
    }
}
