package com.docwei.simpleokhttp

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.docwei.okhttp.*
import java.io.IOException
import java.util.concurrent.*

class MainActivity : AppCompatActivity() {
    var client: OkHttpClient = OkHttpClient.Builder().build();
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun http1Click(view: View) {
        val request: Request = Request.Builder()
            .url("https://www.baidu.com/")
            .get()
            .build()
        client.newCall(request).enqueue(object:Callback{
            override fun onFailure(call: RealCall, e: IOException) {
                 Log.e("okhttp",e.message)
            }
            override fun onResponse(call: RealCall, response: Response) {
                Log.e("okhttp",response?.body?.string())
            }

        })
    }

    fun http2Click(view: View) {

    }


}
