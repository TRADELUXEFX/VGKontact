package com.vgkontact.app

import android.os.Bundle

/**
 * Placeholder destination for the Wallet bottom-nav tab. No wallet
 * feature exists yet - this just gives the tab somewhere real to go
 * (a blank screen showing the wallet icon) so it's visibly clickable.
 * Replace the body of this screen once the wallet feature is built.
 */
class WalletActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wallet)

        BottomNavHelper.setup(this, BottomNavHelper.Tab.WALLET)
    }
}

