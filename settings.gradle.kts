pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "HionePlayer"
include(":app")
include(":hardware-settings")
include(":hione-sysbridge")
include(":hioneos-settings")
include(":hioneos-systemui")
include(":hioneos-launcher")
include(":fmradio")
include(":qcom-fmradio-stubs")
include(":hioneos-tv")
include(":hioneos-tools")
include(":hioneos-media")
include(":hioneos-update")
include(":voicecheck")
include(":hioneos-wheel")
include(":hioneos-riot")
