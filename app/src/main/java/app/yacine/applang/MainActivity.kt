package app.yacine.applang

import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import rikka.shizuku.Shizuku

class MainActivity : Activity() {

    data class AppItem(val label: String, val pkg: String) {
        override fun toString() = "$label\n$pkg"
    }

    private val all = ArrayList<AppItem>()
    private val shown = ArrayList<AppItem>()
    private lateinit var adapter: ArrayAdapter<AppItem>
    private lateinit var status: TextView
    private lateinit var search: EditText

    private val binderReceived = Shizuku.OnBinderReceivedListener { runOnUiThread { refreshStatus() } }
    private val binderDead = Shizuku.OnBinderDeadListener { runOnUiThread { refreshStatus() } }
    private val permResult =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refreshStatus() } }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this)
        status.textSize = 16f
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.setOnClickListener { askPermission() }

        search = EditText(this)
        search.hint = "ابحث عن تطبيق"
        search.setSingleLine()
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                filter(s?.toString() ?: "")
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, shown)
        val list = ListView(this)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ -> onPick(shown[pos]) }

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(16), dp(48), dp(16), dp(8))
        root.addView(status)
        root.addView(search)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceived)
            Shizuku.addBinderDeadListener(binderDead)
            Shizuku.addRequestPermissionResultListener(permResult)
        } catch (_: Throwable) {
        }

        refreshStatus()
        loadApps()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    override fun onDestroy() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceived)
            Shizuku.removeBinderDeadListener(binderDead)
            Shizuku.removeRequestPermissionResultListener(permResult)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    private fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) {
        false
    }

    private fun refreshStatus() {
        status.text = when {
            !binderAlive() -> "Shizuku غير مشغّل. شغّله ثم ارجع هنا."
            !granted() -> "اضغط هنا لمنح التطبيق صلاحية Shizuku."
            else -> "Shizuku جاهز ✅ اختر تطبيقاً لتغيير لغته."
        }
    }

    private fun askPermission() {
        try {
            if (binderAlive() && !granted()) {
                if (Shizuku.shouldShowRequestPermissionRationale()) {
                    toast("فعّل صلاحية هذا التطبيق يدوياً من داخل تطبيق Shizuku.")
                } else {
                    Shizuku.requestPermission(100)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun loadApps() {
        Thread {
            val pm = packageManager
            val items = pm.getInstalledApplications(0)
                .filter { pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != packageName }
                .map { AppItem(it.loadLabel(pm).toString(), it.packageName) }
                .sortedBy { it.label.lowercase() }
            runOnUiThread {
                all.clear()
                all.addAll(items)
                filter(search.text.toString())
            }
        }.start()
    }

    private fun filter(q: String) {
        shown.clear()
        shown.addAll(all.filter { it.label.contains(q, true) || it.pkg.contains(q, true) })
        adapter.notifyDataSetChanged()
    }

    // Runs a shell command with Shizuku's privileges.
    private fun shell(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to (out + err).trim()
    }

    private fun onPick(item: AppItem) {
        if (!binderAlive() || !granted()) {
            toast("شغّل Shizuku وامنح التطبيق الصلاحية أولاً.")
            askPermission()
            return
        }
        Thread {
            val current = try {
                shell("cmd locale get-app-locales ${item.pkg}").second
            } catch (e: Throwable) {
                "؟"
            }
            runOnUiThread { showDialog(item, current) }
        }.start()
    }

    private fun showDialog(item: AppItem, current: String) {
        val names = arrayOf("العربية", "Français", "English", "لغة النظام (إلغاء التغيير)")
        val codes = arrayOf("ar", "fr", "en", null)
        AlertDialog.Builder(this)
            .setTitle("${item.label}\n$current")
            .setItems(names) { _, i -> apply(item, codes[i]) }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun apply(item: AppItem, lang: String?) {
        if (!Regex("^[A-Za-z0-9_.]+$").matches(item.pkg)) {
            toast("اسم حزمة غير صالح")
            return
        }
        Thread {
            val base = "cmd locale set-app-locales ${item.pkg}"
            val cmd = if (lang == null) base else "$base --locales $lang"
            val msg = try {
                val (code, out) = shell(cmd)
                if (code == 0) {
                    shell("am force-stop ${item.pkg}")
                    "تم ✅ افتح ${item.label} من جديد"
                } else {
                    "فشل: $out"
                }
            } catch (e: Throwable) {
                "خطأ: ${e.message}"
            }
            runOnUiThread { toast(msg) }
        }.start()
    }
}
