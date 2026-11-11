package com.whykangkang.wrthub.model;

/**
 * PassWall 的一个节点,对应 /etc/config/passwall 里 type=nodes 的段:
 * remarks 是备注名,type 是核心类型(Xray / sing-box / SS 等),
 * protocol 是协议(vmss/vless/trojan/ssr 等,特殊节点以 _ 开头),
 * address/port 是服务器地址与端口。
 */
public class PasswallNode {

    /** uci 段名(全局段的 node 字段存的就是它) */
    public String section = "";
    public String remarks = "";
    public String type = "";
    public String protocol = "";
    public String address = "";
    public String port = "";

    /** 列表主标题:有备注用备注,否则用地址:端口 */
    public String displayName() {
        if (remarks != null && !remarks.trim().isEmpty()) return remarks.trim();
        return endpoint();
    }

    public String endpoint() {
        if (address == null || address.isEmpty()) return section;
        return port == null || port.isEmpty() ? address : address + ":" + port;
    }

    /** 列表副标题:类型 + 协议 + 地址:端口(备注已占主标题时才有意义) */
    public String subtitle() {
        StringBuilder sb = new StringBuilder();
        if (type != null && !type.isEmpty()) sb.append(type);
        if (protocol != null && !protocol.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(protocol);
        }
        if (remarks != null && !remarks.trim().isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(endpoint());
        }
        return sb.toString();
    }
}
