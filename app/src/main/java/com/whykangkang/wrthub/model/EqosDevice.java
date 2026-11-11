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

    /**
     * 按目标的起始地址排序:单个 IP、IP 段(取起始 IP)、CIDR(取网络地址)
     * 都先抽出打头 IPv4 按段做数值比较,避免 .10 排在 .9 前面,
     * 也避免 IP 段被字符串序排到单个 IP 前面;抽不出的(MAC 等)按字符串。
     */
    public static int compareTarget(String a, String b) {
        String ia = leadingIp(a);
        String ib = leadingIp(b);
        if (ia != null && ib != null) {
            int c = compareIp(ia, ib);
            if (c != 0) return c;
        }
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    /** 取目标打头的 IPv4(IP 段取 '-' 前、CIDR 取 '/' 前);不是 IPv4 返回 null */
    private static String leadingIp(String s) {
        if (s == null) return null;
        int end = s.length();
        int dash = s.indexOf('-');
        if (dash >= 0) end = Math.min(end, dash);
        int slash = s.indexOf('/');
        if (slash >= 0) end = Math.min(end, slash);
        String head = s.substring(0, end).trim();
        return isPlainIpv4(head) ? head : null;
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
