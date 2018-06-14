package com.gojuno.commander.android

import com.gojuno.commander.os.Notification
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.describe
import org.jetbrains.spek.api.dsl.it
import rx.Observable

class AdbSpec : Spek({

    describe("process exit notification output") {

        val file = createTempFile().apply {
            deleteOnExit()
            writeText("\ttest \n")
        }

        it("is trimmed") {
            Observable.just<Notification>(Notification.Exit(file))
                    .trimmedOutput()
                    .test()
                    .assertValue("test")
        }

    }

})
