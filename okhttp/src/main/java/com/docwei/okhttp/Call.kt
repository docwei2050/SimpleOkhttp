package com.docwei.okhttp

import okio.Timeout


interface Call:Cloneable{
    fun request():Request

    fun execute():Response

    fun equeue(responseCallback:Callback)

    fun cancel()

    fun isExecuted():Boolean

    fun isCanceled():Boolean

    fun timeout():Timeout

    override fun clone(): Call
    interface Factory{
         fun newCal(request:Request):Call
    }
}