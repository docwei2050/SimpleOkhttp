package com.docwei.okhttp

import java.io.IOError
import java.io.IOException

interface Callback{
    fun onFailure(call:Call,e:IOException)
    fun onResponse(call:Call,response :Response)
}