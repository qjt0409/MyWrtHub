package com.whykangkang.wrthub.ui.services.passwall;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.PasswallApi;

/**
 * PassWall2:与 PassWall 同构(全局段 enabled/node + nodes 段),
 * 只是配置名换成 passwall2,页面实现全部复用 PasswallActivity。
 */
public class Passwall2Activity extends PasswallActivity {

    @Override
    protected String pwConfig() {
        return PasswallApi.CONFIG2;
    }

    @Override
    protected int pwTitleRes() {
        return R.string.svc_passwall2;
    }

    @Override
    protected Class<?> serverActivityClass() {
        return Passwall2ServerActivity.class;
    }
}
