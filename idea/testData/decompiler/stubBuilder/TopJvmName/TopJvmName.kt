@file:kotlin.jvm.JvmName("Strange")
package some.test

import kotlin.coroutines.experimental.*

@SinceKotlin("1.2")
@Suppress("UNCHECKED_CAST")
fun <T> (suspend () -> T).foo(completion: Continuation<T>): Continuation<Unit> = null!!

// JVM_FILE_NAME: Strange