plugins {
    application
    id("com.gradleup.shadow") version "8.3.2"
}

val bot_version: String by properties
val jda_version: String by properties
val mongodb_version: String by properties
val tika_version: String by properties
val jackson_version: String by properties
val jtokkit_version: String by properties
val openai_version: String by properties
val googleai_version: String by properties
val logback_version: String by properties
val okhttp_version: String by properties

application.mainClass = "discord.mian.Main"
group = "discord.mian"
version = bot_version

repositories{
    mavenLocal()
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies{
    implementation("io.github.freya022:JDA:4c869c0fd8")
    implementation("org.mongodb:mongodb-driver-sync:$mongodb_version")
//    implementation("net.dv8tion:JDA:$jda_version")
    implementation("org.apache.tika:tika-core:$tika_version")
    implementation(platform("com.squareup.okhttp3:okhttp-bom:$okhttp_version"))
    implementation("com.fasterxml.jackson.core:jackson-core:$jackson_version");
    implementation("com.knuddels:jtokkit:$jtokkit_version")
    implementation("io.github.sashirestela:simple-openai:$openai_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.isIncremental = true

    // Set this to the version of java you want to use,
    // the minimum required for JDA is 1.8
    sourceCompatibility = "21"
}