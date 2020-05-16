package com.docwei.okhttp

import java.io.IOException

interface Callback{
    fun onFailure(call:RealCall,e:IOException)
    fun onResponse(call:RealCall,response :Response)
}