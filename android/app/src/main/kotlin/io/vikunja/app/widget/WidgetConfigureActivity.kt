package io.vikunja.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.Toast
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.antonborri.home_widget.HomeWidgetBackgroundIntent
import es.antonborri.home_widget.HomeWidgetPlugin
import io.vikunja.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class WidgetProject(val id: Int, val title: String)

class WidgetConfigureActivity : Activity() {
    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private var projects: List<WidgetProject> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContentView(R.layout.widget_configure)

        val prefs = HomeWidgetPlugin.getData(this)

        val projectsJson = prefs.getString("WidgetProjects", null)
        if (projectsJson != null) {
            try {
                val type = object : TypeToken<List<WidgetProject>>() {}.type
                val parsed: List<WidgetProject>? = Gson().fromJson(projectsJson, type)
                projects = parsed?.filterIsInstance<WidgetProject>() ?: emptyList()
            } catch (e: Exception) {
                projects = emptyList()
            }
        }

        val currentView = prefs.getString("widget_view_$appWidgetId", "today") ?: "today"
        val currentProjectId = prefs.getString("widget_project_id_$appWidgetId", "0")?.toIntOrNull() ?: 0

        val scrollView = findViewById<ScrollView>(R.id.scroll_view)
        val radioGroup = findViewById<RadioGroup>(R.id.view_radio_group)
        val projectSpinner = findViewById<Spinner>(R.id.project_spinner)
        val projectLayout = findViewById<View>(R.id.project_layout)
        val saveButton = findViewById<Button>(R.id.save_button)

        val projectNames = projects.map { it.title }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, projectNames)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        projectSpinner.adapter = adapter

        val radioProject = findViewById<RadioButton>(R.id.radio_project)
        radioProject.isEnabled = projects.isNotEmpty()

        when (currentView) {
            "inbox" -> radioGroup.check(R.id.radio_inbox)
            "upcoming" -> radioGroup.check(R.id.radio_upcoming)
            "project" -> {
                radioGroup.check(R.id.radio_project)
                projectLayout.visibility = View.VISIBLE
                val idx = projects.indexOfFirst { it.id == currentProjectId }
                if (idx >= 0) projectSpinner.setSelection(idx)
            }
            else -> radioGroup.check(R.id.radio_today)
        }

        // Reset scroll to top after layout pass (prevents auto-scroll to checked radio)
        scrollView.post { scrollView.scrollTo(0, 0) }

        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            projectLayout.visibility =
                if (checkedId == R.id.radio_project) View.VISIBLE else View.GONE
        }

        saveButton.setOnClickListener {
            val viewName = when (radioGroup.checkedRadioButtonId) {
                R.id.radio_inbox -> "inbox"
                R.id.radio_upcoming -> "upcoming"
                R.id.radio_project -> "project"
                else -> "today"
            }

            if (viewName == "project" && projects.isEmpty()) {
                Toast.makeText(
                    this,
                    "No projects available yet. Please try again in a moment.",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }

            val editor = prefs.edit()
            editor.putString("widget_view_$appWidgetId", viewName)

            var savedProjectId = currentProjectId
            var savedProjectTitle: String? = null
            if (viewName == "project") {
                val project = projects[projectSpinner.selectedItemPosition]
                savedProjectId = project.id
                savedProjectTitle = project.title
                editor.putString("widget_project_id_$appWidgetId", project.id.toString())
                editor.putString("widget_project_name_$appWidgetId", project.title)
            }

            // A changed view (different view type, or a different
            // project/filter) invalidates the cached list: publish the new
            // title with a loading state and drop the stale cache — the same
            // optimistic swap the view picker performs — so the recompose
            // below never renders the old view's tasks under the new title.
            // Saving the same view keeps the cache; only a leftover error
            // state is cleared so it doesn't flash until the update lands.
            val viewChanged = viewName != currentView || savedProjectId != currentProjectId
            if (viewChanged) {
                editor.putString(
                    "widget_title_$appWidgetId",
                    viewDisplayTitle(viewName, savedProjectTitle),
                )
                editor.putString("widget_state_$appWidgetId", "loading")
                editor.remove("WidgetTasks_$appWidgetId")
            } else {
                editor.remove("widget_state_$appWidgetId")
            }

            val widgetIdsJson = prefs.getString("WidgetIds", "[]") ?: "[]"
            val listType = object : TypeToken<MutableList<String>>() {}.type
            val widgetIds: MutableList<String> =
                Gson().fromJson(widgetIdsJson, listType) ?: mutableListOf()
            if (!widgetIds.contains(appWidgetId.toString())) {
                widgetIds.add(appWidgetId.toString())
            }
            editor.putString("WidgetIds", Gson().toJson(widgetIds))
            editor.apply()

            // Recompose this instance right away: the saved preferences are
            // native-side data, so the widget reflects the save immediately
            // instead of waiting for the background fetch round-trip.
            CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
                recomposeWidgetInstance(this@WidgetConfigureActivity, appWidgetId)
            }

            val uri = "vikunja-app://updatewidget?widgetId=$appWidgetId".toUri()
            HomeWidgetBackgroundIntent.getBroadcast(this, uri).send()
            // The background pipeline's own rerender can be lost in a Glance
            // session race; sweep so the fetched list reaches the surface.
            scheduleRefreshSweeps(this, appWidgetId)

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }

    /** The title the Dart pipeline would write for a view, published natively. */
    private fun viewDisplayTitle(viewName: String, projectTitle: String? = null): String =
        when (viewName) {
            "inbox" -> "Inbox"
            "today" -> "Today"
            "upcoming" -> "Upcoming"
            "project" -> projectTitle ?: "Project"
            else -> "Vikunja"
        }
}
