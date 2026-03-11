rootProject.name = "nexa-dex"

// Include SDK for development — switch to Maven artifact for production
includeBuild("../nexa-ai-sdk") {
    dependencySubstitution {
        substitute(module("org.nexa.sdk:sdk")).using(project(":sdk"))
        substitute(module("org.nexa.sdk:types")).using(project(":types"))
        substitute(module("org.nexa.sdk:api")).using(project(":api"))
    }
}

include(":core")
include(":data")
include(":service")
include(":api")
include(":indexer")
include(":app")
