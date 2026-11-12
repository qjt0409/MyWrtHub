package com.whykangkang.wrthub.ui.services.passwall;

/** PassWall2 的服务端用户管理(数据在 passwall2_server) */
public class Passwall2ServerUsersActivity extends PasswallServerUsersActivity {

    @Override
    protected String pwConfig() {
        return "passwall2";
    }

    @Override
    protected Class<?> userEditActivityClass() {
        return Passwall2ServerUserEditActivity.class;
    }
}
