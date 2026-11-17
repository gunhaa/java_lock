plugins {
	java
	id("org.springframework.boot") version "4.1.2"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "com.gunha"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.javadoc {
	// 예제 클래스 Javadoc의 {@snippet file=...}이 테스트 코드를 가져온다.
	val snippetDir = file("src/test/java")
	inputs.dir(snippetDir)
	(options as StandardJavadocDocletOptions).apply {
		isLinkSource = true
		addStringOption("-snippet-path", snippetDir.absolutePath)
	}
}
