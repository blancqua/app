package io.vikunja.app.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.net.toUri
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import es.antonborri.home_widget.HomeWidgetBackgroundIntent
import es.antonborri.home_widget.HomeWidgetPlugin
import io.vikunja.app.R

data class WidgetProject(val id: Int, val title: String)

private sealed class SpinnerRow {
    data class Header(val title: String) : SpinnerRow()
    data class Project(val project: WidgetProject) : SpinnerRow()
}

private class GroupedProjectAdapter(
    context: Context,
    private val rows: List<SpinnerRow>,
) : ArrayAdapter<SpinnerRow>(context, android.R.layout.simple_spinner_item, rows) {

    private val inflater = LayoutInflater.from(context)

    override fun isEnabled(position: Int): Boolean =
        getItem(position) is SpinnerRow.Project

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = super.getView(position, convertView, parent)
        if (view is TextView) {
            view.text = displayTitle(position)
        }
        return view
    }

    override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
        val row = getItem(position)
        if (row is SpinnerRow.Header) {
            val view = (convertView as? TextView)
                ?: inflater.inflate(
                    android.R.layout.simple_list_item_1,
                    parent,
                    false,
                ) as TextView
            view.text = row.title
            view.setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            view.isEnabled = false
            return view
        }
        val view = super.getDropDownView(position, convertView, parent)
        if (view is TextView) {
            view.text = displayTitle(position)
            view.typeface = Typeface.DEFAULT
            view.isEnabled = true
        }
        return view
    }

    fun displayTitle(position: Int): String =
        (getItem(position) as? SpinnerRow.Project)?.project?.title ?: ""

    fun positionOfProjectId(projectId: Int): Int =
        rows.indexOfFirst { it is SpinnerRow.Project && it.project.id == projectId }

    fun projectAt(position: Int): WidgetProject? =
        (rows.getOrNull(position) as? SpinnerRow.Project)?.project
}

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

        val realProjects = projects.filter { it.id > 0 }
        val savedFilters = projects.filter { it.id < 0 }
        val rows = buildList {
            if (realProjects.isNotEmpty()) {
                add(SpinnerRow.Header("Projects"))
                realProjects.forEach { add(SpinnerRow.Project(it)) }
            }
            if (savedFilters.isNotEmpty()) {
                add(SpinnerRow.Header("Saved Filters"))
                savedFilters.forEach { add(SpinnerRow.Project(it)) }
            }
        }
        val adapter = GroupedProjectAdapter(this, rows)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        projectSpinner.adapter = adapter

        // Position 0 is a section header; always start on a selectable row so
        // saving without touching the spinner persists a real project.
        val firstSelectable = rows.indexOfFirst { it is SpinnerRow.Project }
        if (firstSelectable >= 0) projectSpinner.setSelection(firstSelectable)

        val radioProject = findViewById<RadioButton>(R.id.radio_project)
        radioProject.isEnabled = rows.isNotEmpty()

        when (currentView) {
            "inbox" -> radioGroup.check(R.id.radio_inbox)
            "upcoming" -> radioGroup.check(R.id.radio_upcoming)
            "project" -> {
                radioGroup.check(R.id.radio_project)
                projectLayout.visibility = View.VISIBLE
                val idx = adapter.positionOfProjectId(currentProjectId)
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

            if (viewName == "project" && rows.isEmpty()) {
                Toast.makeText(
                    this,
                    "No projects available yet. Please try again in a moment.",
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }

            val editor = prefs.edit()
            editor.putString("widget_view_$appWidgetId", viewName)

            if (viewName == "project") {
                val project = adapter.projectAt(projectSpinner.selectedItemPosition)
                    ?: return@setOnClickListener
                editor.putString("widget_project_id_$appWidgetId", project.id.toString())
                editor.putString("widget_project_name_$appWidgetId", project.title)
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

            val uri = "vikunja-app://updatewidget?widgetId=$appWidgetId".toUri()
            HomeWidgetBackgroundIntent.getBroadcast(this, uri).send()

            val result = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            setResult(RESULT_OK, result)
            finish()
        }
    }
}
