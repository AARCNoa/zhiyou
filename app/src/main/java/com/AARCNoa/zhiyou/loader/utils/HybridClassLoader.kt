package com.AARCNoa.zhiyou.loader.utils

import com.AARCNoa.zhiyou.utils.reflection.ClassLoaders

object HybridClassLoader : ClassLoader(ClassLoaders.BOOT) {

    private val bootClassLoader = ClassLoaders.BOOT
    lateinit var moduleParentClassLoader: ClassLoader
    lateinit var hostClassLoader: ClassLoader

    override fun findClass(name: String): Class<*> {
        try {
            return bootClassLoader.loadClass(name)
        } catch (_: ClassNotFoundException) {}

        if (::moduleParentClassLoader.isInitialized) {
            try {
                return moduleParentClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        if (::hostClassLoader.isInitialized) {
            try {
                return hostClassLoader.loadClass(name)
            } catch (_: ClassNotFoundException) {}
        }

        throw ClassNotFoundException(name)
    }
}
