package io.vikunja.app.widget

import es.antonborri.home_widget.HomeWidgetGlanceState
import es.antonborri.home_widget.HomeWidgetGlanceStateDefinition
import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.appwidget.CheckBox
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.components.TitleBar
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.google.gson.Gson
import es.antonborri.home_widget.HomeWidgetBackgroundIntent
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import androidx.glance.ImageProvider
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.components.CircleIconButton
import androidx.glance.action.clickable
import io.vikunja.app.MainActivity
import io.vikunja.app.R
import androidx.glance.appwidget.action.ActionCallback
import io.vikunja.app.EXTRA_TASK_ID
import io.vikunja.app.INTENT_TYPE_ADD_TASK
import io.vikunja.app.INTENT_TYPE_OPEN_TASK
import androidx.glance.appwidget.state.updateAppWidgetState
import es.antonborri.home_widget.HomeWidgetPlugin
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val taskID = parameters[taskId] ?: return
        if (taskID == "null") return

        // Optimistic completion: mark the row ticked and recompose right
        // away, so the tick doesn't revert while the server round-trip and
        // the background refresh are still in flight.
        AppWidget.markCompleting(taskID)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        recomposeWidgetInstance(context, appWidgetId)

        val prefs = HomeWidgetPlugin.getData(context)
        prefs.edit {
            putString("completeTask", taskID)
            commit()
        }
        val uri = "vikunja-app://completeTask".toUri()
        val taskURI = uri.buildUpon().appendQueryParameter("taskID", taskID).build()
        val backgroundIntent = HomeWidgetBackgroundIntent.getBroadcast(
            context, taskURI
        )
        backgroundIntent.send()
        scheduleRefreshSweeps(context, appWidgetId)
    }

    companion object {
        val taskId = ActionParameters.Key<String>("task_id")
    }
}

class InteractiveAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_INSERT
            type = INTENT_TYPE_ADD_TASK
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

class ConfigureWidgetAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val intent = Intent(context, WidgetConfigureActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

class SwitchViewAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(glanceId)
        val intent = Intent(context, WidgetViewPickerActivity::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    }
}

/**
 * Refreshes one widget instance's Glance state straight from the home-widget
 * preferences and recomposes it — the same sequence home_widget's receiver
 * runs when Dart calls updateWidget, usable without waiting for the
 * background isolate.
 */
internal suspend fun recomposeWidgetInstance(context: Context, appWidgetId: Int) {
    val glanceId = GlanceAppWidgetManager(context).getGlanceIdBy(appWidgetId)
    AppWidget().apply {
        val stateDefinition = stateDefinition as HomeWidgetGlanceStateDefinition
        updateAppWidgetState<HomeWidgetGlanceState>(
            context,
            stateDefinition,
            glanceId,
        ) { currentState -> currentState }
        update(context, glanceId)
    }
}

/**
 * Best-effort recomposes scheduled after a background update was requested.
 *
 * The plugin's own rerender — Dart's HomeWidget.updateWidget at the end of
 * the background pipeline — races with Glance session work (observed losing
 * the update outright during widget placement) and depends on a WorkManager
 * job the OS may defer. Each pass here re-reads the current preferences and
 * recomposes, so whenever the fetched data lands, a recompose follows within
 * seconds instead of waiting for the next app open.
 */
internal fun scheduleRefreshSweeps(context: Context, appWidgetId: Int) {
    val handler = Handler(Looper.getMainLooper())
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    for (delayMs in longArrayOf(2_000, 5_000, 10_000, 20_000)) {
        handler.postDelayed(
            {
                scope.launch {
                    try {
                        recomposeWidgetInstance(context, appWidgetId)
                    } catch (e: Exception) {
                        Log.d(
                            "Widget",
                            "refresh sweep for widget $appWidgetId failed: ${e.message}",
                        )
                    }
                }
            },
            delayMs,
        )
    }
}

class AppWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(WidgetLayouts.sizeCandidates)
    private var todayTasks: MutableList<Task> = ArrayList()
    private var otherTasks: MutableList<Task> = ArrayList()

    companion object {
        private const val COMPLETING_TTL_MS = 2 * 60 * 1000L

        /**
         * Task ids ticked on the widget surface whose server round-trip hasn't
         * been reflected in the cached task list yet. Process-wide on purpose:
         * the task is as good as done on every instance showing it. Rows in
         * this set render ticked and struck through immediately; entries are
         * dropped once the cached list no longer contains the task (the
         * completion landed) or when they outlive [COMPLETING_TTL_MS] (the
         * completion presumably failed — show the task as open again).
         */
        private val completingTasks = ConcurrentHashMap<String, Long>()

        fun isCompleting(taskId: String) = completingTasks.containsKey(taskId)

        fun markCompleting(taskId: String) {
            completingTasks[taskId] = System.currentTimeMillis()
        }

        /** Forgets completions that landed or expired, judged against the
         * ids currently present in one instance's cached task list. */
        private fun pruneCompletions(cachedIds: Set<String>) {
            val now = System.currentTimeMillis()
            for (taskId in completingTasks.keys.toList()) {
                val landed = taskId !in cachedIds
                val expired = now - (completingTasks[taskId] ?: 0L) > COMPLETING_TTL_MS
                if (landed || expired) completingTasks.remove(taskId)
            }
        }
    }

    override val stateDefinition: GlanceStateDefinition<*>
        get() = HomeWidgetGlanceStateDefinition()

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            GlanceContent(context, currentState(), appWidgetId)
        }
    }

    // This function cannot be composable otherwise it wont run sometimes when shared prefs isn't changed
    private fun getTasks(prefs: SharedPreferences, appWidgetId: Int) {
        // These need to be cleared in case this gets run multiple times
        todayTasks.clear()
        otherTasks.clear()
        val gson = Gson()
        val tasksJson = prefs.getString("WidgetTasks_$appWidgetId", null)

        if (tasksJson != null) {
            val tasks = try {
                gson.fromJson(tasksJson, Array<Task>::class.java)
            } catch (e: Exception) {
                Log.d("Widget", "Failed to parse cached tasks for widget $appWidgetId", e)
                null
            }

            if (tasks != null && tasks.isNotEmpty()) {
                pruneCompletions(tasks.map { it.id }.toSet())
                for (task in tasks) {
                    if (task.today) {
                        todayTasks.add(task)
                    } else {
                        otherTasks.add(task)
                    }
                }
            }
        } else {
            Log.d("Widget", "No tasks found for widget $appWidgetId")
        }
    }

    private fun openTaskIntent(context: Context, taskId: String): Intent =
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_INSERT
            type = INTENT_TYPE_OPEN_TASK
            // The id travels both as an extra and in the data URI: the URI
            // survives PendingIntent round-trips by design and keeps every
            // row's PendingIntent unique, mirroring the completeTask action.
            data = "vikunja-app://openTask".toUri().buildUpon()
                .appendQueryParameter("taskID", taskId).build()
            putExtra(EXTRA_TASK_ID, taskId)
        }

    @Composable
    private fun GlanceContent(
        context: Context,
        currentState: HomeWidgetGlanceState,
        appWidgetId: Int,
    ) {
        val prefs = currentState.preferences
        getTasks(prefs, appWidgetId)

        val size = LocalSize.current
        val sectionCount = listOf(todayTasks, otherTasks).count { it.isNotEmpty() }
        val layout = WidgetLayouts.forSize(
            widthDp = size.width.value.toInt(),
            heightDp = size.height.value.toInt(),
            sectionCount = sectionCount,
        )
        Log.d(
            "Widget",
            "layout ${size.width.value.toInt()}x${size.height.value.toInt()} " +
                "of widget $appWidgetId: $layout",
        )
        val viewType = prefs.getString("widget_view_$appWidgetId", "today") ?: "today"
        val widgetTitle = prefs.getString("widget_title_$appWidgetId", "Vikunja") ?: "Vikunja"
        // Written by the Dart update pipeline: 'error' means the configured
        // project or saved filter is gone for good (403/404) — show an
        // explicit error instead of the stale cached list or "No tasks".
        // 'loading' is written by the view picker while the freshly chosen
        // view's tasks are being fetched.
        val isViewStateError = prefs.getString("widget_state_$appWidgetId", "ok") == "error"
        val isLoadingView = prefs.getString("widget_state_$appWidgetId", "ok") == "loading"
        val widgetTheme =
            WidgetTheme.fromPref(prefs.getString("widget_theme_$appWidgetId", null))
        val widgetOpacity =
            WidgetOpacity.fromPref(prefs.getString("widget_opacity_$appWidgetId", null))
        val widgetDynamicColor =
            WidgetDynamicColor.fromPref(prefs.getString("widget_dynamic_color_$appWidgetId", null))
        val dynamicPalette =
            if (widgetDynamicColor) WidgetDynamicColors.palette(context) else null
        val colors = WidgetColors.forTheme(widgetTheme, widgetOpacity, dynamicPalette)
        val otherSectionLabel = when (viewType) {
            "upcoming" -> "This Week:"
            "inbox", "project" -> "Tasks:"
            else -> "Overdue:"
        }

        // Single background paint at the root: rows and the list used to paint
        // the same surface color again, which is invisible while opaque but
        // composites (bands) once the surface carries transparency.
        Column(
            modifier = GlanceModifier.fillMaxSize().background(colors.surface),
            verticalAlignment = Alignment.Top
        ) {
            WidgetTitleBar(widgetTitle, colors)
            // Sizes too short for a task row render the title bar alone
            // instead of squeezing a clipped half row into the instance.
            if (!layout.isHeaderOnly) {
                if (isViewStateError) {
                    ErrorView(colors)
                } else if (isLoadingView) {
                    LoadingView(colors)
                } else if (todayTasks.isEmpty() and otherTasks.isEmpty()) {
                    EmptyView(colors)
                } else {
                    TaskList(context, prefs, colors, layout, otherSectionLabel)
                }
            }
        }
    }

    @Composable
    private fun TaskList(
        context: Context,
        prefs: SharedPreferences,
        colors: WidgetColors,
        layout: WidgetLayout,
        otherSectionLabel: String,
    ) {
        LazyColumn(
            modifier = GlanceModifier.fillMaxHeight().padding(8.dp)
        ) {
            if (todayTasks.isNotEmpty()) {
                if (layout.showSectionLabels) {
                    item {
                        SectionLabel("Today:", colors, layout)
                    }
                }
                items(todayTasks.sortedBy { it.dueDate ?: Long.MAX_VALUE }) { task ->
                    RenderRow(context, task, prefs, colors, layout, showFullDate = false)
                }
            }
            if (otherTasks.isNotEmpty()) {
                if (layout.showSectionLabels) {
                    item {
                        SectionLabel(otherSectionLabel, colors, layout)
                    }
                }
                items(otherTasks.sortedBy { it.dueDate ?: Long.MAX_VALUE }) { task ->
                    RenderRow(context, task, prefs, colors, layout, showFullDate = true)
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(label: String, colors: WidgetColors, layout: WidgetLayout) {
        Text(
            label,
            style = TextStyle(
                fontSize = layout.sectionLabelFontSizeSp.sp, color = colors.text
            ),
        )
    }

    @Composable
    private fun WidgetTitleBar(title: String = "Vikunja", colors: WidgetColors) {
        Box(
            modifier = GlanceModifier
                .background(colors.titleBarBackground),
            contentAlignment = Alignment.Center,
        ) {
            TitleBar(
                title = title,
                startIcon = ImageProvider(R.drawable.vikunja_logo),
                iconColor = null,
                textColor = colors.titleBarText,
                actions = {
                    Box(
                        modifier = GlanceModifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircleIconButton(
                            enabled = true,
                            onClick = actionRunCallback<SwitchViewAction>(),
                            imageProvider = ImageProvider(R.drawable.expand_more),
                            contentDescription = "Switch view",
                        )
                    }
                    Box(
                        modifier = GlanceModifier.padding(end = 4.dp, top = 4.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircleIconButton(
                            enabled = true,
                            onClick = actionRunCallback<ConfigureWidgetAction>(),
                            imageProvider = ImageProvider(R.drawable.settings),
                            contentDescription = "Configure widget",
                        )
                    }
                    Box(
                        modifier = GlanceModifier.padding(end = 8.dp, top = 4.dp, bottom = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircleIconButton(
                            enabled = true,
                            onClick = actionRunCallback<InteractiveAction>(),
                            imageProvider = ImageProvider(R.drawable.add),
                            contentDescription = "Add a Task",
                        )
                    }
                },
            )
        }
    }

    @Composable
    private fun RenderRow(
        context: Context,
        task: Task,
        prefs: SharedPreferences,
        colors: WidgetColors,
        layout: WidgetLayout,
        showFullDate: Boolean = false,
    ) {
        // Ticked on this surface but the completion hasn't been reflected in
        // the cached list yet: keep the row visibly done instead of letting
        // the checkbox revert while the background refresh is in flight.
        val completing = isCompleting(task.id)
        Row(
            modifier = GlanceModifier.fillMaxWidth().padding(layout.rowPaddingDp.dp)
                .clickable(actionStartActivity(openTaskIntent(context, task.id))),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CheckBox(
                checked = completing,
                onCheckedChange = actionRunCallback<CompleteTaskAction>(
                    parameters = actionParametersOf(CompleteTaskAction.taskId to task.id)
                ),
                modifier = GlanceModifier.padding(start = 0.dp)
            )
            val taskDueDate = task.dueDateAsDate()
            if (taskDueDate != null && layout.showDueDates) {
                Box(
                    modifier = GlanceModifier.padding(start = 8.dp)
                ) {
                    Text(
                        text = formatDueDate(taskDueDate, showFullDate), style = TextStyle(
                            fontSize = layout.taskFontSizeSp.sp, color = colors.text
                        )
                    )
                }
            }
            Box(
                modifier = GlanceModifier.padding(start = 8.dp)
            ) {
                Text(
                    text = task.title, style = TextStyle(
                        fontSize = layout.taskFontSizeSp.sp, color = colors.text
                    ), maxLines = 1
                )
            }
        }
    }

    private fun formatDueDate(dueDate: Date, showFullDate: Boolean): String {
        if (showFullDate) {
            val pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "MM dd j:m")
            val formatter = DateTimeFormatter.ofPattern(pattern)
            return dueDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().format(
                formatter
            )
        } else {
            return dueDate.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime().format(
                DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
            )
        }
    }

    @Composable
    private fun EmptyView(colors: WidgetColors) {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "No tasks", style = TextStyle(
                    fontSize = 16.sp, color = colors.text
                )
            )
        }
    }

    @Composable
    private fun LoadingView(colors: WidgetColors) {
        Box(
            modifier = GlanceModifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Loading…", style = TextStyle(
                    fontSize = 16.sp, color = colors.text
                )
            )
        }
    }

    @Composable
    private fun ErrorView(colors: WidgetColors) {
        Box(
            modifier = GlanceModifier.fillMaxSize().padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column {
                Text(
                    text = "Couldn't load this view", style = TextStyle(
                        fontSize = 16.sp, color = colors.text
                    )
                )
                Text(
                    text = "It may have been deleted. Tap ⚙ to pick another.",
                    style = TextStyle(
                        fontSize = 13.sp, color = colors.text
                    ),
                    maxLines = 2,
                )
            }
        }
    }
}
