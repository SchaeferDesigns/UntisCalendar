package de.schaeferdesigns.untiscalendar

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var server: EditText
    private lateinit var school: EditText
    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var calendarSpinner: Spinner
    private lateinit var autoSync: MaterialSwitch
    private lateinit var status: TextView
    private lateinit var syncButton: Button
    private lateinit var removeButton: Button

    private var calendars: List<CalendarInfo> = emptyList()

    private val permissionRequest =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadCalendars() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)

        server = findViewById(R.id.server)
        school = findViewById(R.id.school)
        username = findViewById(R.id.username)
        password = findViewById(R.id.password)
        calendarSpinner = findViewById(R.id.calendar)
        autoSync = findViewById(R.id.autoSync)
        status = findViewById(R.id.status)
        syncButton = findViewById(R.id.syncButton)
        removeButton = findViewById(R.id.removeButton)

        server.setText(settings.server)
        school.setText(settings.school)
        username.setText(settings.username)
        if (settings.hasPassword) password.hint = getString(R.string.password_saved)
        autoSync.isChecked = settings.autoSync

        syncButton.setOnClickListener { saveAndSync() }
        removeButton.setOnClickListener { confirmRemove() }
        findViewById<Button>(R.id.reloadCalendars).setOnClickListener { loadCalendars() }
    }

    override fun onResume() {
        super.onResume()
        status.text = settings.lastStatus
        if (hasCalendarPermission()) {
            loadCalendars()
        } else {
            permissionRequest.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR))
        }
    }

    private fun hasCalendarPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    private fun loadCalendars() {
        if (!hasCalendarPermission()) {
            status.text = getString(R.string.permission_missing)
            return
        }
        calendars = CalendarStore(this).writableCalendars()
        calendarSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, calendars)
        val selected = calendars.indexOfFirst { it.id == settings.calendarId }
            .takeIf { it >= 0 }
            ?: calendars.indexOfFirst { it.name.contains("untis", ignoreCase = true) }
        if (selected >= 0) calendarSpinner.setSelection(selected)
    }

    private fun saveAndSync() {
        val calendar = calendars.getOrNull(calendarSpinner.selectedItemPosition)
        if (calendar == null) {
            toastStatus(getString(R.string.no_calendar))
            return
        }
        if (username.text.isBlank() || (!settings.hasPassword && password.text.isEmpty())) {
            toastStatus(getString(R.string.missing_login))
            return
        }

        settings.server = UntisClient.normalizeServer(server.text.toString())
        settings.school = school.text.toString().trim()
        settings.username = username.text.toString().trim()
        if (password.text.isNotEmpty()) settings.password = password.text.toString()
        settings.calendarId = calendar.id
        settings.autoSync = autoSync.isChecked
        password.text.clear()
        password.hint = getString(R.string.password_saved)

        if (autoSync.isChecked) SyncWorker.schedule(this) else SyncWorker.cancel(this)

        setBusy(true, getString(R.string.syncing))
        lifecycleScope.launch {
            val message = try {
                SyncRunner.sync(this@MainActivity)
            } catch (e: IOException) {
                "Keine Verbindung zu Untis: ${e.message}"
            } catch (e: Exception) {
                "Fehler: ${e.message}"
            }
            settings.lastStatus = message
            setBusy(false, message)
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_title)
            .setMessage(R.string.remove_message)
            .setPositiveButton(R.string.remove_confirm) { _, _ ->
                setBusy(true, getString(R.string.removing))
                lifecycleScope.launch {
                    val message = try {
                        val count = SyncRunner.removeAll(this@MainActivity)
                        withContext(Dispatchers.Main) {
                            autoSync.isChecked = false
                        }
                        settings.autoSync = false
                        SyncWorker.cancel(this@MainActivity)
                        "$count Einträge entfernt. Automatischer Abgleich ist aus."
                    } catch (e: Exception) {
                        "Fehler: ${e.message}"
                    }
                    settings.lastStatus = message
                    setBusy(false, message)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun setBusy(busy: Boolean, message: String) {
        syncButton.isEnabled = !busy
        removeButton.isEnabled = !busy
        status.text = message
    }

    private fun toastStatus(message: String) {
        status.text = message
    }
}
