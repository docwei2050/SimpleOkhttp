package com.docwei.simpleokhttp

import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.docwei.okhttp.*
import com.docwei.okhttp.MediaType.Companion.toMediaType
import com.docwei.okhttp.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class MainActivity : AppCompatActivity() {
    var client: OkHttpClient = OkHttpClient.Builder().build();
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }

    fun http1_1Click(view: View) {
        val request: Request = Request.Builder()
            .url("https://www.baidu.com/")
            .get()
            .build()
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("okhttp", e.message)
            }

            override fun onResponse(call: Call, response: Response) {
                Log.e("okhttp", response?.body?.string())
            }

        })
    }

    fun http2Click(view: View) {
        var json = JSONObject();
        json.put("name", "docwei")
        json.put("password", "123456")
        val request: Request = Request.Builder()
            .url("https://www.httpbin.org/post")
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall2(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("okhttp", e.message)
            }
            @Throws(IOException::class)
            override fun onResponse(call: Call, response: Response) { //  Log.e("okhttp", "onResponse: " + response.body().toString());
                Log.e("okhttp", response.body?.string())
            }
        })
    }


}
