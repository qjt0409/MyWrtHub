package com.whykangkang.wrthub.ui.services.passwall;

/** PassWall2 的服务端用户编辑(数据在 passwall2_server) */
public class Passwall2ServerUserEditActivity extends PasswallServerUserEditActivity {

    @Override
    protected String pwConfig() {
        return "passwall2";
    }
}
