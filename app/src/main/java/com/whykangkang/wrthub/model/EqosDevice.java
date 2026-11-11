package com.whykangkang.wrthub.model;

/**
 * 一条设备限速规则,对应 eqosplus 的 device 段(字段以固件源码为准):
 *
 *  - 匹配键是 {@code mac} 字段,但里面可以填单个 IP、MAC,或 IP 段
 *    (如 192.168.10.10-192.168.10.200),所以这里叫 target;
 *  - 启用开关的 uci 键是 {@code enable}(不是 enabled);
 *  - download/upload 单位 MB/s,可带小数(如 0.1),用字符串保存;
 *  - timestart/timeend 是 "HH:MM",week 是 "0"(每天)/"1".."7"/"1,2,3,4,5" 等。
 */
public class EqosDevice {

    /** uci 段名(匿名段如 cfg0392bd)。新建时为空,写入后由路由器回填。 */
    public String section = "";
    /** 每条规则自己的启用开关(对应 uci 的 enable) */
    public boolean enabled = true;
    /** IP / MAC / IP 段(对应 uci 的 mac 字段) */
    public String target = "";
    public String download = "";   // MB/s
    public String upload = "";     // MB/s
    public String comment = "";
    public String timeStart = "00:00";
    public String timeEnd = "23:55";
    public String week = "0";

    /** 列表主标题:有备注用备注,否则用目标 */
    public String displayName() {
        return comment == null || comment.trim().isEmpty() ? target : comment.trim();
    }

    /** 纯 IPv4 目标按段做数值比较,避免 .10 排在 .9 前面;其余按字符串 */
    public static int compareTarget(String a, String b) {
        if (isPlainIpv4(a) && isPlainIpv4(b)) return compareIp(a, b);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    static boolean isPlainIpv4(String s) {
        if (s == null) return false;
        String[] parts = s.split("\\.", -1);
        if (parts.length != 4) return false;
        for (String p : parts) {
            if (p.isEmpty() || p.length() > 3) return false;
            for (int i = 0; i < p.length(); i++) {
                if (!Character.isDigit(p.charAt(i))) return false;
            }
        }
        return true;
    }

    /** IP 按段做数值比较,避免 .10 排在 .9 前面 */
    public static int compareIp(String a, String b) {
        String[] pa = a.split("\\.");
        String[] pb = b.split("\\.");
        int n = Math.min(pa.length, pb.length);
        for (int i = 0; i < n; i++) {
            int va = parse(pa[i]);
            int vb = parse(pb[i]);
            if (va != vb) return Integer.compare(va, vb);
        }
        return Integer.compare(pa.length, pb.length);
    }

    private static int parse(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
