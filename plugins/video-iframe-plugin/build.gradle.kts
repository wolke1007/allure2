plugins {
    `java-library-distribution`
}

description = "Allure Video Iframe Plugin"

artifacts.add("allurePlugin", tasks.distZip)
artifacts.add("archives", tasks.distZip)
