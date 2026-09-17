package com.vgkontact.app

import androidx.appcompat.app.AppCompatActivity

/**
 * Base class for every screen in the app.
 *
 * Applies the app-wide font (currently Poppins - see FontHelper) to
 * every screen automatically, with no per-Activity wiring required.
 *
 * HOW: setContentView() is overridden here, not in each Activity.
 * Android calls setContentView() as part of normal onCreate() flow in
 * every subclass, so overriding it once here means every screen picks
 * up the font call for free - subclasses just extend BaseActivity
 * instead of AppCompatActivity and don't need to do anything else.
 *
 * TO CHANGE THE APP FONT LATER: edit FontHelper.kt only. Nothing in
 * this file, or in any Activity, needs to change.
 */
open class BaseActivity : AppCompatActivity() {

    override fun setContentView(layoutResID: Int) {
        super.setContentView(layoutResID)
        FontHelper.applyPoppinsAsync(this, findViewById(android.R.id.content))
    }

    override fun setContentView(view: android.view.View?) {
        super.setContentView(view)
        view?.let { FontHelper.applyPoppinsAsync(this, it) }
    }

    override fun setContentView(view: android.view.View?, params: android.view.ViewGroup.LayoutParams?) {
        super.setContentView(view, params)
        view?.let { FontHelper.applyPoppinsAsync(this, it) }
    }
}
