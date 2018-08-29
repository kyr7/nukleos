package me.cyber.nukleos.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.app.ActivityCompat
import android.support.v4.app.Fragment
import android.support.v4.content.ContextCompat
import android.widget.Toast
import com.github.paolorotolo.appintro.AppIntro
import com.github.paolorotolo.appintro.AppIntroFragment
import com.github.paolorotolo.appintro.model.SliderPage
import me.cyber.nukleus.R

private const val PREFS_GLOBAL = "global"
private const val KEY_COMPLETED_ONBOARDING = "completed_onboarding"
private const val REQUEST_LOCATION_CODE = 1

class IntroActivity : AppIntro() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val backgroundColor =
                ContextCompat.getColor(this, R.color.primaryColor)

        val page0 = SliderPage()
        page0.title = getString(R.string.onboarding_title_0)
        page0.description = getString(R.string.onboarding_description_0)
        page0.imageDrawable = R.drawable.abc_ab_share_pack_mtrl_alpha
        page0.bgColor = backgroundColor
        addSlide(AppIntroFragment.newInstance(page0))

        val page1 = SliderPage()
        page1.title = getString(R.string.find)
        page1.description = getString(R.string.onboarding_description_1)
        page1.imageDrawable = R.drawable.abc_spinner_mtrl_am_alpha
        page1.bgColor = backgroundColor
        addSlide(AppIntroFragment.newInstance(page1))

        val page2 = SliderPage()
        page2.title = getString(R.string.sensor_control)
        page2.description = getString(R.string.onboarding_description_2)
        page2.imageDrawable = R.drawable.ic_done_white
        page2.bgColor = backgroundColor
        addSlide(AppIntroFragment.newInstance(page2))

        val page3 = SliderPage()
        page3.title = getString(R.string.sensor_charts)
        page3.description = getString(R.string.onboarding_description_3)
        page3.imageDrawable = R.drawable.ic_download
        page3.bgColor = backgroundColor
        addSlide(AppIntroFragment.newInstance(page3))

        val page4 = SliderPage()
        page4.title = getString(R.string.learning)
        page4.description = getString(R.string.onboarding_description_4)
        page4.imageDrawable = R.drawable.ic_download
        page4.bgColor = backgroundColor
        addSlide(AppIntroFragment.newInstance(page4))

        setBarColor(ContextCompat.getColor(this, R.color.primaryDarkColor))
        setSeparatorColor(ContextCompat.getColor(this, R.color.primaryLightColor))
        showSkipButton(false)
        isProgressButtonEnabled = true
        setVibrate(true)
        setVibrateIntensity(30)
    }

    override fun onSkipPressed(currentFragment: Fragment) {
        super.onSkipPressed(currentFragment)
        saveOnBoardingCompleted()
        requestPermission()
    }

    override fun onDonePressed(currentFragment: Fragment) {
        super.onDonePressed(currentFragment)
        saveOnBoardingCompleted()
        requestPermission()
    }

    private fun saveOnBoardingCompleted() {
        val editor = getSharedPreferences(PREFS_GLOBAL, Context.MODE_PRIVATE).edit()
        editor.putBoolean(KEY_COMPLETED_ONBOARDING, true)
        editor.apply()
    }

    private fun requestPermission() {
        val hasPermission = (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED)
        if (hasPermission) {
            startMainActivity()
        } else {
            ActivityCompat.requestPermissions(this,
                    arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION),
                    REQUEST_LOCATION_CODE)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        when (requestCode) {
            REQUEST_LOCATION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startMainActivity()
                } else {
                    Toast.makeText(this, getString(R.string.location_permission_denied), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun startMainActivity() {
        finish()
        startActivity(Intent(this, MainActivity::class.java))
    }
}