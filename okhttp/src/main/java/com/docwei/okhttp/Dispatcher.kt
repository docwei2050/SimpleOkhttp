package com.docwei.okhttp

import java.util.concurrent.*

class Dispatcher {
    val executorService: ExecutorService by lazy {
        ThreadPoolExecutor(0, Int.MAX_VALUE, 60, TimeUnit.SECONDS, SynchronousQueue(),
            ThreadFactory {
                Thread(it, "okhttp").apply {
                    isDaemon=false
                }
            })
    }

}