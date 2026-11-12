package com.whykangkang.wrthub.ui.services.passwall;

import com.whykangkang.wrthub.R;
import com.whykangkang.wrthub.api.PasswallApi;

/** PassWall2 服务端实例编辑:与 PassWall 同构,仅配置名不同,实现全部复用。 */
public class Passwall2ServerEditActivity extends PasswallServerEditActivity {

    @Override
    protected String pwConfig() {
        return PasswallApi.CONFIG2;
    }

    @Override
    protected int pwTitleRes() {
        return R.string.svc_passwall2;
    }
}
