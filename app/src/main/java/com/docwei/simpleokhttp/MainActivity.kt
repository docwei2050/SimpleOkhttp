package com.docwei.simpleokhttp

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.docwei.okhttp.OkHttpClient
import com.docwei.okhttp.Request
import java.util.concurrent.*

class MainActivity : AppCompatActivity() {
    var client: OkHttpClient = OkHttpClient.Builder().build();
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    private var executorServiceOrNull: ExecutorService? = null

    @get:Synchronized
    @get:JvmName("executorService")
    val executorService: ExecutorService
        get() {
            if (executorServiceOrNull == null) {
                executorServiceOrNull = ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS,
                    SynchronousQueue(), ThreadFactory { runnable ->
                        Thread(runnable, "okhttp").apply {
                            isDaemon = false
                        }
                    })
            }
            return executorServiceOrNull!!
        }

    fun clickSyn(view: View) {
        val request: Request = Request.Builder()
            .url("https://www.baidu.com/")
            .get()
            .build()

        client.newCall(request).enqueue()
    }


    fun http2Click(view: View) {

    }


}
