package com.fieldlog.powerdebug.ui

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.fieldlog.powerdebug.R
import com.fieldlog.powerdebug.databinding.ActivityMainBinding
import com.fieldlog.powerdebug.ui.device.DeviceFragment
import com.fieldlog.powerdebug.ui.log.LogListFragment
import com.fieldlog.powerdebug.ui.tools.ToolsFragment

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) show(LogListFragment())

        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_logs -> show(LogListFragment())
                R.id.nav_device -> show(DeviceFragment())
                R.id.nav_tools -> show(ToolsFragment())
            }
            true
        }
        binding.fabNewLog.setOnClickListener {
            startActivity(
                android.content.Intent(this, com.fieldlog.powerdebug.ui.log.LogEditActivity::class.java)
            )
        }
    }

    private fun show(f: Fragment) {
        supportFragmentManager.commit {
            replace(R.id.container, f)
        }
    }
}
