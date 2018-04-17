package com.benny.openlauncher.activity


import android.app.*
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.res.Resources
import android.graphics.Point
import android.graphics.PointF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.support.v4.widget.DrawerLayout
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import cat.ereza.customactivityoncrash.CustomActivityOnCrash
import com.afollestad.materialdialogs.MaterialDialog
import com.benny.openlauncher.AppObject
import com.benny.openlauncher.BuildConfig
import com.benny.openlauncher.R
import com.benny.openlauncher.interfaces.AppDeleteListener
import com.benny.openlauncher.interfaces.AppUpdateListener
import com.benny.openlauncher.interfaces.DialogListener
import com.benny.openlauncher.manager.Setup
import com.benny.openlauncher.model.Item
import com.benny.openlauncher.model.PopupIconLabelItem
import com.benny.openlauncher.util.*
import com.benny.openlauncher.viewutil.*
import com.benny.openlauncher.widget.*
import com.benny.openlauncher.widget.Desktop
import com.mikepenz.fastadapter.listeners.OnClickListener
import kotlinx.android.synthetic.main.activity_home.*
import kotlinx.android.synthetic.main.view_drawer_indicator.*
import kotlinx.android.synthetic.main.view_home.*
import net.gsantner.opoc.util.ContextUtils
import java.util.*

class Home : Activity(), Desktop.OnDesktopEditListener, DesktopOptionView.DesktopOptionViewListener, DrawerLayout.DrawerListener {

    companion object {
        var launcher: Home? = null
        var _resources: Resources? = null
        val REQUEST_PICK_APPWIDGET = 0x6475
        val REQUEST_CREATE_APPWIDGET = 0x3648
        public val REQUEST_PERMISSION_STORAGE = 0x2678

        // static members, easier to access from any activity and class
        lateinit var db: Setup.DataManager
        var appWidgetHost: WidgetHost? = null
        lateinit var appWidgetManager: AppWidgetManager

        // used for the drag shadow builder
        var itemTouchX = 0f
        var itemTouchY = 0f
        var consumeNextResume: Boolean = false

        private val timeChangesIntentFilter: IntentFilter = IntentFilter()
        private val appUpdateIntentFilter: IntentFilter = IntentFilter()
        private val shortcutIntentFilter: IntentFilter = IntentFilter()

        init {
            timeChangesIntentFilter.addAction(Intent.ACTION_TIME_TICK)
            timeChangesIntentFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED)
            timeChangesIntentFilter.addAction(Intent.ACTION_TIME_CHANGED)

            appUpdateIntentFilter.addAction(Intent.ACTION_PACKAGE_ADDED)
            appUpdateIntentFilter.addAction(Intent.ACTION_PACKAGE_REMOVED)
            appUpdateIntentFilter.addAction(Intent.ACTION_PACKAGE_CHANGED)
            appUpdateIntentFilter.addDataScheme("package")

            shortcutIntentFilter.addAction("com.android.launcher.action.INSTALL_SHORTCUT")
        }
    }

    fun getDrawerLayout(): DrawerLayout = drawer_layout

    override fun onCreate(savedInstanceState: Bundle?) {
        launcher = this
        _resources = this.resources

        ContextUtils(applicationContext).setAppLanguage(AppSettings.get().language) // before setContentView
        super.onCreate(savedInstanceState)

        if (!Setup.wasInitialised())
            initStaticHelper()

        if (Setup.appSettings().isSearchBarTimeEnabled) {
            timeChangedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    if (action == Intent.ACTION_TIME_TICK) {
                        updateSearchClock()
                    }
                }
            }
        }
        launcher = this
        db = Setup.dataManager()

        setContentView(layoutInflater.inflate(R.layout.activity_home, null))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION

        init()

        if (BuildConfig.IS_GPLAY_BUILD) {
            CustomActivityOnCrash.setShowErrorDetails(true)
            CustomActivityOnCrash.setEnableAppRestart(false)
            CustomActivityOnCrash.setDefaultErrorActivityDrawable(R.drawable.rip)
            CustomActivityOnCrash.install(this)
        }
    }

    fun onStartApp(context: Context, intent: Intent, view: View? = null) {
        if (intent.component!!.packageName == "com.benny.openlauncher") {
            LauncherAction.RunAction(LauncherAction.Action.LauncherSettings, context)
            consumeNextResume = true
        } else {
            try {
                context.startActivity(intent, getActivityAnimationOpts(view))

                Home.consumeNextResume = true
            } catch (e: Exception) {
                Tool.toast(context, R.string.toast_app_uninstalled)
            }
        }
    }

    fun onStartApp(context: Context, app: App, view: View? = null) {
        if (app.packageName == "com.benny.openlauncher") {
            LauncherAction.RunAction(LauncherAction.Action.LauncherSettings, context)
            consumeNextResume = true
        } else {
            try {
                val intent = Intent(Intent.ACTION_MAIN)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                intent.setClassName(app.packageName, app.className)

                context.startActivity(intent, getActivityAnimationOpts(view))

                Home.consumeNextResume = true
            } catch (e: Exception) {
                Tool.toast(context, R.string.toast_app_uninstalled)
            }
        }
    }

    protected open fun initAppManager() {
        Setup.appLoader().addUpdateListener(AppUpdateListener {
            if (desktop == null)
                return@AppUpdateListener false

            if (Setup.appSettings().desktopStyle != Desktop.DesktopMode.SHOW_ALL_APPS) {
                if (Setup.appSettings().isAppFirstLaunch) {
                    Setup.appSettings().isAppFirstLaunch = false

                    // create a new app drawer button
                    val appDrawerBtnItem = Item.newActionItem(Definitions.ACTION_LAUNCHER)

                    // center the button
                    appDrawerBtnItem.x = Definitions.DOCK_DEFAULT_CENTER_ITEM_INDEX_X
                    db.saveItem(appDrawerBtnItem, 0, Definitions.ItemPosition.Dock)
                }
            }
            if (Setup.appSettings().desktopStyle == Desktop.DesktopMode.NORMAL) {
                desktop.initDesktopNormal(this@Home)
            } else if (Setup.appSettings().desktopStyle == Desktop.DesktopMode.SHOW_ALL_APPS) {
                desktop.initDesktopShowAll(this@Home, this@Home)
            }
            dock.initDockItem(this@Home)

            // remove this listener
            true
        })
        Setup.appLoader().addDeleteListener(AppDeleteListener {
            if (Setup.appSettings().desktopStyle == Desktop.DesktopMode.NORMAL) {
                desktop.initDesktopNormal(this@Home)
            } else if (Setup.appSettings().desktopStyle == Desktop.DesktopMode.SHOW_ALL_APPS) {
                desktop.initDesktopShowAll(this@Home, this@Home)
            }
            dock.initDockItem(this@Home)
            setToHomePage()
            false
        })
        AppManager.getInstance(this).init()
    }

    // called to initialize the views
    protected open fun initViews() {
        initSearchBar()
        initDock()

        appDrawerController.init()

        appDrawerController.setHome(this)
        dragOptionPanel.setHome(this)

        desktop.init()
        desktop.desktopEditListener = this

        desktopEditOptionPanel.setDesktopOptionViewListener(this)
        desktopEditOptionPanel.updateLockIcon(Setup.appSettings().isDesktopLock)
        desktop.addOnPageChangeListener(object : SmoothViewPager.OnPageChangeListener {
            override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

            override fun onPageSelected(position: Int) {
                desktopEditOptionPanel.updateHomeIcon(Setup.appSettings().desktopPageCurrent == position)
            }

            override fun onPageScrollStateChanged(state: Int) {}
        })

        desktop!!.setPageIndicator(desktopIndicator)

        dragOptionPanel.setAutoHideView(searchBar)

        appDrawerController!!.setCallBack(object : AppDrawerController.CallBack {
            override fun onStart() {
                Tool.visibleViews(appDrawerIndicator)
                Tool.invisibleViews(desktop)
                hideDesktopIndicator()
                updateDock(false)
                updateSearchBar(false)
            }

            override fun onEnd() {}
        }, object : AppDrawerController.CallBack {
            override fun onStart() {
                Tool.invisibleViews(appDrawerIndicator)
                Tool.visibleViews(desktop)
                showDesktopIndicator()
                if (Setup.appSettings().drawerStyle == AppDrawerController.DrawerMode.HORIZONTAL_PAGED)
                    updateDock(true, 200)
                else
                    updateDock(true)
                updateSearchBar(!dragOptionPanel.isDraggedFromDrawer)
                dragOptionPanel.isDraggedFromDrawer = false
            }

            override fun onEnd() {
                if (!Setup.appSettings().isDrawerRememberPosition) {
                    appDrawerController!!.scrollToStart()
                }
                appDrawerController!!.drawer.visibility = View.INVISIBLE
            }
        })

        initMinibar()
    }

    fun initSettings() {

        updateHomeLayout()

        if (Setup.appSettings().isDesktopFullscreen) {
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        } else {
            window.setFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN, WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
        }

        desktop!!.setBackgroundColor(Setup.appSettings().desktopBackgroundColor)
        dock!!.setBackgroundColor(Setup.appSettings().dockColor)

        appDrawerController!!.setBackgroundColor(Setup.appSettings().drawerBackgroundColor)
        appDrawerController!!.background.alpha = 0
        appDrawerController!!.reloadDrawerCardTheme()

        when (Setup.appSettings().drawerStyle) {
            AppDrawerController.DrawerMode.HORIZONTAL_PAGED -> if (!Setup.appSettings().isDrawerShowIndicator) {
                appDrawerController!!.getChildAt(1).visibility = View.GONE
            }
            AppDrawerController.DrawerMode.VERTICAL -> {
            }
        }// handled in the AppDrawerVertical class
        drawer_layout.setDrawerLockMode(if (AppSettings.get().minibarEnable) DrawerLayout.LOCK_MODE_UNLOCKED else DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
    }

    override fun onRemovePage() {
        if (!getDesktop().isCurrentPageEmpty)
            DialogHelper.alertDialog(this, getString(R.string.remove),
                    "This page is not empty. Those item will also be removed.",
                    MaterialDialog.SingleButtonCallback { _, _ ->
                        desktop!!.removeCurrentPage()
                    }
            )
        else
            desktop!!.removeCurrentPage()
    }

    fun initMinibar() {
        val labels = ArrayList<String>()
        val icons = ArrayList<Int>()

        for (act in AppSettings.get().minibarArrangement) {
            if (act.length > 1 && act[0] == '0') {
                val item = LauncherAction.getActionItemFromString(act.substring(1))
                if (item != null) {
                    labels.add(item.label.toString())
                    icons.add(item.icon)
                }
            }
        }

        minibar.adapter = IconListAdapter(this, labels, icons)
        minibar.onItemClickListener = AdapterView.OnItemClickListener { _, _, i, _ ->
            val action = LauncherAction.Action.valueOf(labels[i])
            if (action == LauncherAction.Action.DeviceSettings || action == LauncherAction.Action.LauncherSettings || action == LauncherAction.Action.EditMinBar) {
                consumeNextResume = true
            }
            LauncherAction.RunAction(action, this@Home)
            if (action != LauncherAction.Action.DeviceSettings && action != LauncherAction.Action.LauncherSettings && action != LauncherAction.Action.EditMinBar) {
                drawer_layout.closeDrawers()
            }
        }
        // frame layout spans the entire side while the minibar container has gaps at the top and bottom
        minibar_background.setBackgroundColor(AppSettings.get().minibarBackgroundColor)
    }

    override fun onBackPressed() {
        handleLauncherPause(false)
        drawer_layout.closeDrawers()
    }

    // search button in the search bar is clicked
    fun onSearch(view: View) {
        var i: Intent
        try {
            i = Intent(Intent.ACTION_MAIN)
            i.setClassName("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.SearchActivity")
            this@Home.startActivity(i)
        } catch (e: Exception) {
            i = Intent(Intent.ACTION_WEB_SEARCH)
            i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        this@Home.startActivity(i)
    }

    // voice button in the search bar clicked
    fun onVoiceSearch(view: View) {
        try {
            val i = Intent(Intent.ACTION_MAIN)
            i.setClassName("com.google.android.googlequicksearchbox", "com.google.android.googlequicksearchbox.VoiceSearchActivity")
            this@Home.startActivity(i)
        } catch (e: Exception) {
            Tool.toast(this@Home, "Can not find google search app")
        }

    }

    override fun onDrawerSlide(drawerView: View, slideOffset: Float) {}

    override fun onDrawerOpened(drawerView: View) {}

    override fun onDrawerClosed(drawerView: View) {}

    override fun onDrawerStateChanged(newState: Int) {}

    fun initStaticHelper() {
        val appSettings = AppSettings.get()
        val imageLoader = object : Setup.ImageLoader {
            override fun createIconProvider(drawable: Drawable?): BaseIconProvider = SimpleIconProvider(drawable)

            override fun createIconProvider(icon: Int): BaseIconProvider = SimpleIconProvider(icon)
        }
        val desktopGestureCallback = DesktopGestureListener.DesktopGestureCallback { desktop, event ->
            var gestureid: Int
            when (event) {
                DesktopGestureListener.Type.SwipeUp -> {
                    gestureid = appSettings.gestureSwipeUp
                    if (gestureid != 0) {
                        val gesture = LauncherAction.getActionItem(gestureid - 1)
                        if (gesture != null && appSettings.isGestureFeedback) {
                            Tool.vibrate(desktop)
                        }
                        if (gestureid == 9) {
                            gesture.extraData = Intent(packageManager.getLaunchIntentForPackage(appSettings.getString(getString(R.string.pref_key__gesture_swipe_up) + "__", "")))
                        }
                        LauncherAction.RunAction(gesture, desktop.context)
                    }
                    true
                }
                DesktopGestureListener.Type.SwipeDown -> {
                    gestureid = appSettings.gestureSwipeDown
                    if (gestureid != 0) {
                        val gesture = LauncherAction.getActionItem(gestureid - 1)
                        if (gesture != null && appSettings.isGestureFeedback) {
                            Tool.vibrate(desktop)
                        }
                        if (gestureid == 9) {
                            gesture.extraData = Intent(packageManager.getLaunchIntentForPackage(appSettings.getString(getString(R.string.pref_key__gesture_swipe_down) + "__", "")))
                        }
                        LauncherAction.RunAction(gesture, desktop.context)
                    }
                    true
                }
                DesktopGestureListener.Type.SwipeLeft -> false
                DesktopGestureListener.Type.SwipeRight -> false
                DesktopGestureListener.Type.Pinch -> {
                    gestureid = appSettings.gestureSwipeDown
                    if (gestureid != 0) {
                        val gesture = LauncherAction.getActionItem(gestureid - 1)
                        if (gesture != null && appSettings.isGestureFeedback) {
                            Tool.vibrate(desktop)
                        }
                        if (gestureid == 9) {
                            gesture.extraData = Intent(packageManager.getLaunchIntentForPackage(appSettings.getString(getString(R.string.pref_key__gesture_pinch) + "__", "")))
                        }
                        LauncherAction.RunAction(gesture, desktop.context)
                    }
                    true
                }
                DesktopGestureListener.Type.Unpinch -> {
                    gestureid = appSettings.gestureSwipeDown
                    if (gestureid != 0) {
                        val gesture = LauncherAction.getActionItem(gestureid - 1)
                        if (gesture != null && appSettings.isGestureFeedback) {
                            Tool.vibrate(desktop)
                        }
                        if (gestureid == 9) {
                            gesture.extraData = Intent(packageManager.getLaunchIntentForPackage(appSettings.getString(getString(R.string.pref_key__gesture_unpinch) + "__", "")))
                        }
                        LauncherAction.RunAction(gesture, desktop.context)
                    }
                    true
                }
                DesktopGestureListener.Type.DoubleTap -> {
                    gestureid = appSettings.gestureSwipeDown
                    if (gestureid != 0) {
                        val gesture = LauncherAction.getActionItem(gestureid - 1)
                        if (gesture != null && appSettings.isGestureFeedback) {
                            Tool.vibrate(desktop)
                        }
                        if (gestureid == 9) {
                            gesture.extraData = Intent(packageManager.getLaunchIntentForPackage(appSettings.getString(getString(R.string.pref_key__gesture_double_tap) + "__", "")))
                        }
                        LauncherAction.RunAction(gesture, desktop.context)
                    }
                    true
                }
                else -> {
                    throw RuntimeException("Type not handled!")
                }
            }
        }
        val itemGestureCallback: ItemGestureListener.ItemGestureCallback = ItemGestureListener.ItemGestureCallback { _, _ -> false }
        val dataManager = DatabaseHelper(this)
        val appLoader = AppManager.getInstance(this)
        val eventHandler = object : Setup.EventHandler {
            override fun showLauncherSettings(context: Context) {
                LauncherAction.RunAction(LauncherAction.Action.LauncherSettings, context)
            }

            override fun showPickAction(context: Context, listener: DialogListener.OnAddAppDrawerItemListener) {
                DialogHelper.addActionItemDialog(context, MaterialDialog.ListCallback { _, _, position, _ ->
                    when (position) {
                        0 -> listener.onAdd()
                    }
                })
            }

            override fun showEditDialog(context: Context, item: Item, listener: DialogListener.OnEditDialogListener) {
                DialogHelper.editItemDialog("Edit Item", item.label, context, object : DialogHelper.OnItemEditListener {
                    override fun itemLabel(label: String) {
                        listener.onRename(label)
                    }
                })
            }

            override fun showDeletePackageDialog(context: Context, item: Item) {
                DialogHelper.deletePackageDialog(context, item)
            }
        }
        val logger = object : Setup.Logger {
            override fun log(source: Any, priority: Int, tag: String?, msg: String, vararg args: Any) {
                Log.println(priority, tag, String.format(msg, *args))
            }
        }
        Setup.init(object : Setup() {
            override fun getAppContext(): Context = AppObject.get()!!.applicationContext

            override fun getAppSettings(): AppSettings = AppSettings.get()

            override fun getDesktopGestureCallback(): DesktopGestureListener.DesktopGestureCallback = desktopGestureCallback

            override fun getItemGestureCallback(): ItemGestureListener.ItemGestureCallback = itemGestureCallback

            override fun getImageLoader(): ImageLoader = imageLoader

            override fun getDataManager(): Setup.DataManager = dataManager

            override fun getAppLoader(): AppManager = appLoader

            override fun getEventHandler(): Setup.EventHandler = eventHandler

            override fun getLogger(): Setup.Logger = logger
        })
    }

    override fun onResume() {
        super.onResume()

        if (Setup.appSettings().appRestartRequired) {
            Setup.appSettings().appRestartRequired = false

            val restartIntent = Intent(this, Home::class.java)
            val restartIntentP = PendingIntent.getActivity(this, 123556, restartIntent, PendingIntent.FLAG_CANCEL_CURRENT)
            val mgr = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, restartIntentP)
            System.exit(0)
            return
        }

        launcher = this
        appWidgetHost?.startListening()

        handleLauncherPause(intent.action == Intent.ACTION_MAIN)


        val user = AppSettings.get().getBool(R.string.pref_key__desktop_rotate, false)
        var system = false
        try {
            system = Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION) == 1
        } catch (e: Settings.SettingNotFoundException) {
            Log.d(Home::class.java.simpleName, "Unable to read settings", e)
        }

        val rotate: Boolean
        if (resources.getBoolean(R.bool.isTablet)) { // tables has no user option to disable rotate
            rotate = system
        } else {
            rotate = user && system
        }
        if (rotate)
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        else
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }


    private val shortcutReceiver = ShortcutReceiver()
    private val appUpdateReceiver = AppUpdateReceiver()
    private var timeChangedReceiver: BroadcastReceiver? = null

    // region for the APP_DRAWER_ANIMATION
    private var cx: Int = 0
    private var cy: Int = 0
    private var rad: Int = 0

    fun getDesktop(): Desktop = desktop
    fun getDock(): Dock = dock
    fun getAppDrawerController(): AppDrawerController = appDrawerController
    fun getGroupPopup(): GroupPopupView = groupPopup
    fun getSearchBar(): SearchBar = searchBar
    fun getBackground(): View = background
    fun getDesktopIndicator(): PagerIndicator = desktopIndicator
    fun getDragNDropView(): DragNDropLayout = dragNDropView

    private fun init() {
        appWidgetHost = WidgetHost(applicationContext, R.id.app_widget_host)
        appWidgetManager = AppWidgetManager.getInstance(this)
        appWidgetHost!!.startListening()

        initViews()

        initDragNDrop()

        registerBroadcastReceiver()

        // add all of the data for the desktop and dock
        initAppManager()

        initSettings()

        System.runFinalization()
        System.gc()
    }

    fun onUninstallItem(item: Item) {
        consumeNextResume = true
        Setup.eventHandler().showDeletePackageDialog(this, item)
    }

    fun onRemoveItem(item: Item) {
        when (item.locationInLauncher) {
            Item.LOCATION_DESKTOP -> {
                desktop.removeItem(desktop.currentPage.coordinateToChildView(Point(item.x, item.y))!!, true)
            }
            Item.LOCATION_DOCK -> {
                dock.removeItem(dock.coordinateToChildView(Point(item.x, item.y))!!, true)
            }
        }

        db.deleteItem(item, true)
    }

    fun onInfoItem(item: Item) {
        if (item.type === Item.Type.APP) {
            try {
                startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + item.intent!!.component!!.packageName)))
            } catch (e: Exception) {
                Tool.toast(this, R.string.toast_app_uninstalled)
            }
        }
    }

    fun onEditItem(item: Item) {
        Setup.eventHandler().showEditDialog(this, item, DialogListener.OnEditDialogListener { name ->
            item.label = name
            db.saveItem(item)

            when (item.locationInLauncher) {
                Item.LOCATION_DESKTOP -> {
                    desktop.removeItem(desktop.currentPage.coordinateToChildView(Point(item.x, item.y))!!, false)
                    desktop.addItemToCell(item, item.x, item.y)
                }
                Item.LOCATION_DOCK -> {
                    dock.removeItem(dock.coordinateToChildView(Point(item.x, item.y))!!, false)
                    dock.addItemToCell(item, item.x, item.y)
                }
            }
        })
    }

    protected open fun initDragNDrop() {
        //dragHandle's drag event
        val dragHandler = Handler()
        dragNDropView.registerDropTarget(object : DragNDropLayout.DropTargetListener(leftDragHandle) {

            val leftRunnable = object : Runnable {
                override fun run() {
                    if (getDesktop().currentItem > 0)
                        getDesktop().currentItem = getDesktop().currentItem - 1
                    else if (getDesktop().currentItem == 0)
                        getDesktop().addPageLeft(true)
                    dragHandler.postDelayed(this, 1000)
                }
            }

            override fun onStart(action: DragAction.Action, location: PointF, isInside: Boolean): Boolean =
                    when (action) {
                        DragAction.Action.APP,
                        DragAction.Action.WIDGET,
                        DragAction.Action.SEARCH_RESULT,
                        DragAction.Action.APP_DRAWER,
                        DragAction.Action.GROUP,
                        DragAction.Action.SHORTCUT,
                        DragAction.Action.ACTION -> {
                            true
                        }
                    }

            override fun onStartDrag(action: DragAction.Action, location: PointF) {
                if (leftDragHandle.alpha == 0f)
                    leftDragHandle.animate().alpha(0.5f)
            }

            override fun onEnter(action: DragAction.Action, location: PointF) {
                dragHandler.post(leftRunnable)
                leftDragHandle.animate().alpha(0.9f)
            }

            override fun onExit(action: DragAction.Action, location: PointF) {
                dragHandler.removeCallbacksAndMessages(null)
                leftDragHandle.animate().alpha(0.5f)
            }

            override fun onEnd() {
                dragHandler.removeCallbacksAndMessages(null)
                leftDragHandle.animate().alpha(0f)
            }
        })
        dragNDropView.registerDropTarget(object : DragNDropLayout.DropTargetListener(rightDragHandle) {

            val rightRunnable = object : Runnable {
                override fun run() {
                    if (getDesktop().currentItem < getDesktop().pageCount - 1)
                        getDesktop().currentItem = getDesktop().currentItem + 1
                    else if (getDesktop().currentItem == getDesktop().pageCount - 1)
                        getDesktop().addPageRight(true)
                    dragHandler.postDelayed(this, 1000)
                }
            }

            override fun onStart(action: DragAction.Action, location: PointF, isInside: Boolean): Boolean =
                    when (action) {
                        DragAction.Action.APP,
                        DragAction.Action.WIDGET,
                        DragAction.Action.SEARCH_RESULT,
                        DragAction.Action.APP_DRAWER,
                        DragAction.Action.GROUP,
                        DragAction.Action.SHORTCUT,
                        DragAction.Action.ACTION -> {
                            true
                        }
                    }

            override fun onStartDrag(action: DragAction.Action, location: PointF) {
                if (rightDragHandle.alpha == 0f)
                    rightDragHandle.animate().alpha(0.5f)
            }

            override fun onEnter(action: DragAction.Action, location: PointF) {
                dragHandler.post(rightRunnable)
                rightDragHandle.animate().alpha(0.9f)
            }

            override fun onExit(action: DragAction.Action, location: PointF) {
                dragHandler.removeCallbacksAndMessages(null)
                rightDragHandle.animate().alpha(0.5f)
            }

            override fun onEnd() {
                dragHandler.removeCallbacksAndMessages(null)
                rightDragHandle.animate().alpha(0f)
            }
        })

        val uninstallItemIdentifier = 83L
        val infoItemIdentifier = 84L
        val editItemIdentifier = 85L
        val removeItemIdentifier = 86L

        val uninstallItem = PopupIconLabelItem(R.string.uninstall, R.drawable.ic_delete_dark_24dp).withIdentifier(uninstallItemIdentifier)
        val infoItem = PopupIconLabelItem(R.string.info, R.drawable.ic_info_outline_dark_24dp).withIdentifier(infoItemIdentifier)
        val editItem = PopupIconLabelItem(R.string.edit, R.drawable.ic_edit_black_24dp).withIdentifier(editItemIdentifier)
        val removeItem = PopupIconLabelItem(R.string.remove, R.drawable.ic_close_dark_24dp).withIdentifier(removeItemIdentifier)

        fun showItemPopup() {
            val itemList = arrayListOf<PopupIconLabelItem>()
            when (dragNDropView.dragItem?.type) {
                Item.Type.APP, Item.Type.SHORTCUT, Item.Type.GROUP -> {
                    if (dragNDropView.dragAction == DragAction.Action.APP_DRAWER) {
                        itemList.add(uninstallItem)
                        itemList.add(infoItem)
                    } else {
                        itemList.add(editItem)
                        itemList.add(removeItem)
                        itemList.add(infoItem)
                    }
                }
                Item.Type.ACTION -> {
                    itemList.add(editItem)
                    itemList.add(removeItem)
                }
                Item.Type.WIDGET -> {
                    itemList.add(removeItem)
                }
            }

            var x = dragNDropView.dragLocation.x - Home.itemTouchX + Tool.toPx(10)
            var y = dragNDropView.dragLocation.y - Home.itemTouchY - Tool.toPx((46 * itemList.size))

            if ((x + Tool.toPx(200)) > dragNDropView.width) {
                dragNDropView.setPopupMenuShowDirection(false)
                x = dragNDropView.dragLocation.x - Home.itemTouchX + desktop.currentPage.cellWidth - Tool.toPx(200).toFloat() - Tool.toPx(10)
            } else {
                dragNDropView.setPopupMenuShowDirection(true)
            }

            if (y < 0)
                y = dragNDropView.dragLocation.y - Home.itemTouchY + desktop.currentPage.cellHeight + Tool.toPx(4)
            else
                y -= Tool.toPx(4)

            dragNDropView.showPopupMenuForItem(x, y, itemList, OnClickListener { v, adapter, item, position ->
                when (item.identifier) {
                    uninstallItemIdentifier -> onUninstallItem(dragNDropView.dragItem!!)
                    editItemIdentifier -> onEditItem(dragNDropView.dragItem!!)
                    removeItemIdentifier -> onRemoveItem(dragNDropView.dragItem!!)
                    infoItemIdentifier -> onInfoItem(dragNDropView.dragItem!!)
                }
                dragNDropView.hidePopupMenu()
                true
            })
        }

        //desktop's drag event
        dragNDropView.registerDropTarget(object : DragNDropLayout.DropTargetListener(desktop) {
            override fun onStart(action: DragAction.Action, location: PointF, isInside: Boolean): Boolean {
                if (action != DragAction.Action.SEARCH_RESULT)
                    showItemPopup()
                return true
            }

            override fun onExit(action: DragAction.Action, location: PointF) {
                for (page in desktop.pages)
                    page.clearCachedOutlineBitmap()
                dragNDropView.cancelFolderPreview()
            }

            override fun onDrop(action: DragAction.Action, location: PointF, item: Item) {
                // this statement makes sure that adding an app multiple times from the app drawer works
                // the app will get a new id every time
                if (action == DragAction.Action.APP_DRAWER) {
                    if (appDrawerController.isOpen) return
                    item.reset()
                }

                val x = location.x.toInt()
                val y = location.y.toInt()
                if (desktop.addItemToPoint(item, x, y)) {
                    desktop.consumeRevert()
                    dock.consumeRevert()
                    // add the item to the database
                    Home.db.saveItem(item, desktop.currentItem, Definitions.ItemPosition.Desktop)
                } else {
                    val pos = Point()
                    desktop.currentPage.touchPosToCoordinate(pos, x, y, item.spanX, item.spanY, false)
                    val itemView = desktop.currentPage.coordinateToChildView(pos)
                    if (itemView != null && Desktop.handleOnDropOver(this@Home, item, itemView.tag as Item, itemView, desktop.currentPage, desktop.currentItem, Definitions.ItemPosition.Desktop, desktop)) {
                        desktop.consumeRevert()
                        dock.consumeRevert()
                    } else {
                        Tool.toast(this@Home, R.string.toast_not_enough_space)
                        desktop.revertLastItem()
                        dock.revertLastItem()
                    }
                }
            }

            override fun onStartDrag(action: DragAction.Action, location: PointF) {
                closeAppDrawer()
            }

            override fun onEnd() {
                Home.launcher?.getDesktopIndicator()?.hideDelay()
                for (page in desktop.pages)
                    page.clearCachedOutlineBitmap()
            }

            override fun onMove(action: DragAction.Action, location: PointF) {
                if (action != DragAction.Action.SEARCH_RESULT && action != DragAction.Action.WIDGET)
                    desktop.updateIconProjection(location.x.toInt(), location.y.toInt())
            }
        })

        //dock's drag event
        dragNDropView.registerDropTarget(object : DragNDropLayout.DropTargetListener(dock) {
            override fun onStart(action: DragAction.Action, location: PointF, isInside: Boolean): Boolean {
                val ok = (action != DragAction.Action.WIDGET)

                if (ok && isInside) {
                    //showItemPopup()
                }

                return ok
            }

            override fun onDrop(action: DragAction.Action, location: PointF, item: Item) {
                if (action == DragAction.Action.APP_DRAWER) {
                    if (appDrawerController.isOpen) return
                    item.reset()
                }

                val x = location.x.toInt()
                val y = location.y.toInt()
                if (dock.addItemToPoint(item, x, y)) {
                    desktop.consumeRevert()
                    dock.consumeRevert()

                    // add the item to the database
                    Home.db.saveItem(item, 0, Definitions.ItemPosition.Dock)
                } else {
                    val pos = Point()
                    dock.touchPosToCoordinate(pos, x, y, item.spanX, item.spanY, false)
                    val itemView = dock.coordinateToChildView(pos)
                    if (itemView != null) {
                        if (Desktop.handleOnDropOver(this@Home, item, itemView.tag as Item, itemView, dock, 0, Definitions.ItemPosition.Dock, dock)) {
                            desktop.consumeRevert()
                            dock.consumeRevert()
                        } else {
                            Tool.toast(this@Home, R.string.toast_not_enough_space)
                            desktop.revertLastItem()
                            dock.revertLastItem()
                        }
                    } else {
                        Tool.toast(this@Home, R.string.toast_not_enough_space)
                        desktop.revertLastItem()
                        dock.revertLastItem()
                    }
                }
            }

            override fun onExit(action: DragAction.Action, location: PointF) {
                dock.clearCachedOutlineBitmap()
                dragNDropView.cancelFolderPreview()
            }

            override fun onEnd() {
                if (dragNDropView.dragAction == DragAction.Action.WIDGET)
                    desktop.revertLastItem()
                dock.clearCachedOutlineBitmap()
            }

            override fun onMove(action: DragAction.Action, location: PointF) {
                if (action != DragAction.Action.SEARCH_RESULT)
                    dock.updateIconProjection(location.x.toInt(), location.y.toInt())
            }
        })
    }

    private fun getActivityAnimationOpts(view: View?): Bundle? {
        if (view == null) return null
        var opts: ActivityOptions? = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            var left = 0
            var top = 0
            var width = view.measuredWidth
            var height = view.measuredHeight
            if (view is AppItemView) {
                width = view.iconSize.toInt()
                left = view.drawIconLeft.toInt()
                top = view.drawIconTop.toInt()
            }
            opts = ActivityOptions.makeClipRevealAnimation(view, left, top, width, height)
        } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            opts = ActivityOptions.makeScaleUpAnimation(view, 0, 0, view.measuredWidth, view.measuredHeight)
        }
        return if (opts != null) opts.toBundle() else null
    }

    override fun onDesktopEdit() {
        Tool.visibleViews(100, 20, desktopEditOptionPanel)

        hideDesktopIndicator()
        updateDock(false)
        updateSearchBar(false)
    }

    override fun onFinishDesktopEdit() {
        Tool.invisibleViews(100, 20, desktopEditOptionPanel)

        desktopIndicator.hideDelay()
        showDesktopIndicator()
        updateDock(true)
        updateSearchBar(true)
    }

    override fun onSetPageAsHome() {
        Setup.appSettings().desktopPageCurrent = desktop!!.currentItem
    }

    override fun onLaunchSettings() {
        consumeNextResume = true
        Setup.eventHandler().showLauncherSettings(this)
    }

    override fun onPickDesktopAction() {
        Setup.eventHandler().showPickAction(this, DialogListener.OnAddAppDrawerItemListener {
            val pos = desktop!!.currentPage.findFreeSpace()
            if (pos != null)
                desktop!!.addItemToCell(Item.newActionItem(Definitions.ACTION_LAUNCHER), pos.x, pos.y)
            else
                Tool.toast(this@Home, R.string.toast_not_enough_space)
        })
    }

    override fun onPickWidget() {
        pickWidget()
    }

    private fun initDock() {
        val iconSize = Setup.appSettings().dockIconSize
        dock!!.init()
        if (Setup.appSettings().isDockShowLabel) {
            dock!!.layoutParams.height = Tool.dp2px(16 + iconSize + 14 + 10, this) + Dock.bottomInset
        } else {
            dock!!.layoutParams.height = Tool.dp2px(16 + iconSize + 10, this) + Dock.bottomInset
        }
    }

    fun dimBackground() {
        Tool.visibleViews(background)
    }

    fun unDimBackground() {
        Tool.invisibleViews(background)
    }

    fun clearRoomForPopUp() {
        Tool.invisibleViews(desktop)
        hideDesktopIndicator()
        updateDock(false)
    }

    fun unClearRoomForPopUp() {
        Tool.visibleViews(desktop)
        showDesktopIndicator()
        updateDock(true)
    }

    private fun initSearchBar() {
        searchBar.setCallback(object : SearchBar.CallBack {
            override fun onInternetSearch(string: String) {
                val intent = Intent()

                if (Tool.isIntentActionAvailable(applicationContext, Intent.ACTION_WEB_SEARCH) && !Setup.appSettings().searchBarForceBrowser) {
                    intent.action = Intent.ACTION_WEB_SEARCH
                    intent.putExtra(SearchManager.QUERY, string)
                } else {
                    val baseUri = Setup.appSettings().searchBarBaseURI
                    val searchUri = if (baseUri.contains("{query}")) baseUri.replace("{query}", string) else baseUri + string

                    intent.action = Intent.ACTION_VIEW
                    intent.data = Uri.parse(searchUri)
                }

                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

            }

            override fun onExpand() {
                clearRoomForPopUp()
                dimBackground()

                searchBar.searchInput.isFocusable = true
                searchBar.searchInput.isFocusableInTouchMode = true
                searchBar.searchInput.post { searchBar.searchInput.requestFocus() }

                Tool.showKeyboard(this@Home, searchBar.searchInput)
            }

            override fun onCollapse() {
                desktop.postDelayed({
                    unClearRoomForPopUp()
                }, 100)
                unDimBackground()

                searchBar.searchInput.clearFocus()

                Tool.hideKeyboard(this@Home, searchBar.searchInput)
            }
        })
        searchBar.searchClock.setOnClickListener { calendarDropDownView.animateShow() }

        // this view is just a text view of the current date
        updateSearchClock()
    }

    @JvmOverloads
    fun updateDock(show: Boolean, delay: Long = 0) = if (Setup.appSettings().dockEnable && show) {
        Tool.visibleViews(100, delay, dock)
        (desktop!!.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = Tool.dp2px(4, this)
        (desktopIndicator!!.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = Tool.dp2px(4, this)
    } else {
        if (Setup.appSettings().dockEnable) {
            Tool.invisibleViews(100, dock)
        } else {
            Tool.goneViews(100, dock)
            (desktopIndicator!!.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = Desktop.bottomInset + Tool.dp2px(4, this)
            (desktop!!.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin = Tool.dp2px(4, this)
        }
    }

    fun updateSearchBar(show: Boolean) = if (Setup.appSettings().searchBarEnable && show) {
        Tool.visibleViews(100, searchBar)
    } else {
        if (Setup.appSettings().searchBarEnable) {
            Tool.invisibleViews(100, searchBar)
        } else {
            Tool.goneViews(searchBar)
        }
    }

    fun updateDesktopIndicatorVisibility() = if (Setup.appSettings().isDesktopShowIndicator) {
        Tool.visibleViews(100, desktopIndicator)
    } else {
        Tool.goneViews(100, desktopIndicator)
    }

    fun hideDesktopIndicator() {
        if (Setup.appSettings().isDesktopShowIndicator)
            Tool.invisibleViews(100, desktopIndicator)
    }

    fun showDesktopIndicator() {
        if (Setup.appSettings().isDesktopShowIndicator)
            Tool.visibleViews(100, desktopIndicator)
    }

    private fun updateSearchClock() {
        if (searchBar!!.searchClock.text != null) {
            try {
                searchBar!!.updateClock()
            } catch (ex: Exception) {
                searchBar.searchClock.setText(R.string.bad_format)
            }
        }
    }

    fun updateHomeLayout() {
        updateSearchBar(true)
        updateDock(true)

        updateDesktopIndicatorVisibility()

        if (!Setup.appSettings().searchBarEnable) {
            (leftDragHandle!!.layoutParams as ViewGroup.MarginLayoutParams).topMargin = Desktop.topInset
            (rightDragHandle!!.layoutParams as ViewGroup.MarginLayoutParams).topMargin = Desktop.topInset
            desktop!!.setPadding(0, Desktop.topInset, 0, 0)
        }

        if (!Setup.appSettings().dockEnable) {
            desktop!!.setPadding(0, 0, 0, Desktop.bottomInset)
        }
    }

    private fun registerBroadcastReceiver() {
        registerReceiver(appUpdateReceiver, appUpdateIntentFilter)
        if (timeChangedReceiver != null) {
            registerReceiver(timeChangedReceiver, timeChangesIntentFilter)
        }
        registerReceiver(shortcutReceiver, shortcutIntentFilter)
    }

    private fun pickWidget() {
        consumeNextResume = true
        val appWidgetId = appWidgetHost!!.allocateAppWidgetId()
        val pickIntent = Intent(AppWidgetManager.ACTION_APPWIDGET_PICK)
        pickIntent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        startActivityForResult(pickIntent, REQUEST_PICK_APPWIDGET)
    }

    private fun configureWidget(data: Intent?) {
        val extras = data!!.extras
        val appWidgetId = extras!!.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        if (appWidgetInfo.configure != null) {
            val intent = Intent(AppWidgetManager.ACTION_APPWIDGET_CONFIGURE)
            intent.component = appWidgetInfo.configure
            intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            startActivityForResult(intent, REQUEST_CREATE_APPWIDGET)
        } else {
            createWidget(data)
        }
    }

    private fun createWidget(data: Intent?) {
        val extras = data!!.extras
        val appWidgetId = extras!!.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
        val appWidgetInfo = appWidgetManager.getAppWidgetInfo(appWidgetId)
        val item = Item.newWidgetItem(appWidgetId)
        item.spanX = (appWidgetInfo.minWidth - 1) / desktop!!.pages[Home.launcher!!.desktop!!.currentItem].cellWidth + 1
        item.spanY = (appWidgetInfo.minHeight - 1) / desktop!!.pages[Home.launcher!!.desktop!!.currentItem].cellHeight + 1
        val point = desktop!!.currentPage.findFreeSpace(item.spanX, item.spanY)
        if (point != null) {
            item.x = point.x
            item.y = point.y

            // add item to database
            db.saveItem(item, desktop!!.currentItem, Definitions.ItemPosition.Desktop)
            desktop!!.addItemToPage(item, desktop!!.currentItem)
        } else {
            Tool.toast(this@Home, R.string.toast_not_enough_space)
        }
    }

    override fun onDestroy() {
        appWidgetHost?.stopListening()
        appWidgetHost = null
        unregisterReceiver(appUpdateReceiver)
        if (timeChangedReceiver != null) {
            unregisterReceiver(timeChangedReceiver)
        }
        unregisterReceiver(shortcutReceiver)
        launcher = null

        super.onDestroy()
    }

    override fun onLowMemory() {
        System.runFinalization()
        System.gc()
        super.onLowMemory()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_PICK_APPWIDGET) {
                configureWidget(data)
            } else if (requestCode == REQUEST_CREATE_APPWIDGET) {
                createWidget(data)
            }
        } else if (resultCode == Activity.RESULT_CANCELED && data != null) {
            val appWidgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1)
            if (appWidgetId != -1) {
                appWidgetHost?.deleteAppWidgetId(appWidgetId)
            }
        }
    }

    override fun onStart() {
        launcher = this
        appWidgetHost?.startListening()
        super.onStart()
    }

    private fun handleLauncherPause(wasHomePressed: Boolean) {
        if (consumeNextResume && !wasHomePressed) {
            consumeNextResume = false
            return
        }

        onHandleLauncherPause()
    }

    protected open fun onHandleLauncherPause() {
        groupPopup.dismissPopup()
        calendarDropDownView.animateHide()
        dragNDropView.hidePopupMenu()
        if (searchBar.collapse()) return

        if (desktop != null) {
            if (!desktop.inEditMode) {
                if (appDrawerController.drawer.visibility == View.VISIBLE) {
                    closeAppDrawer()
                } else {
                    setToHomePage()
                }
            } else {
                desktop.pages[desktop.currentItem].performClick()
            }
        }
    }

    private fun setToHomePage() {
        desktop.currentItem = Setup.appSettings().desktopPageCurrent
    }

    @JvmOverloads
    fun openAppDrawer(view: View? = desktop, x: Int = -1, y: Int = -1) {
        if (!(x > 0 && y > 0)) {
            val pos = IntArray(2)
            view!!.getLocationInWindow(pos)
            cx = pos[0]
            cy = pos[1]

            cx += view.width / 2
            cy += view.height / 2
            if (view is AppItemView) {
                val appItemView = view as AppItemView?
                if (appItemView!!.showLabel) {
                    cy -= Tool.dp2px(14, this) / 2
                }
                rad = (appItemView.iconSize / 2 - Tool.toPx(4)).toInt()
            }
            cx -= (appDrawerController!!.drawer.layoutParams as ViewGroup.MarginLayoutParams).leftMargin
            cy -= (appDrawerController!!.drawer.layoutParams as ViewGroup.MarginLayoutParams).topMargin
            cy -= appDrawerController!!.paddingTop
        } else {
            cx = x
            cy = y
            rad = 0
        }
        val finalRadius = Math.max(appDrawerController!!.drawer.width, appDrawerController!!.drawer.height)
        appDrawerController!!.open(cx, cy, rad, finalRadius)
    }

    fun closeAppDrawer() {
        val finalRadius = Math.max(appDrawerController!!.drawer.width, appDrawerController!!.drawer.height)
        appDrawerController!!.close(cx, cy, rad, finalRadius)
    }

}
