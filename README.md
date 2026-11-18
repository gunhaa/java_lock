# java_lock

Java의 락과 동기화 API를 **예제 코드 + 테스트**로 학습하는 프로젝트입니다.

- 예제 클래스(`src/main`)의 주석에 각 도구가 **JVM**(바이트코드, 객체 헤더, AQS)과 **OS**(park, futex, 컨텍스트 스위치)에서 어떻게 동작하는지 정리했습니다.
- 예제마다 테스트(`src/test`)가 있어 상호 배제, 대기 상태, 재진입 같은 성질을 직접 확인합니다.

> **처음이라면 [선행 지식 문서](docs/README.md)부터 읽어 보세요.** 스레드 모델, 자바 메모리 모델(JMM), 캐시와 OS 대기 구조를 정리해 두었습니다. 예제 주석에 나오는 용어는 모두 여기서 설명합니다.

## 환경

| 항목 | 버전 |
|---|---|
| Java | 21 (Temurin) |
| Spring Boot | 4.1.2 ([start.spring.io](https://start.spring.io)로 생성) |
| JUnit | 6 (Spring Boot가 버전 관리) |
| 빌드 | Gradle 9 (Kotlin DSL, Wrapper 포함) |

Spring Boot는 프로젝트 뼈대로만 씁니다. 예제와 테스트는 순수 Java·JUnit으로 작성했습니다.

## 실행

```bash
# 전체 테스트
./gradlew test

# 패키지 하나만
./gradlew test --tests 'com.gunha.lock.monitor.*'

# 클래스 하나만
./gradlew test --tests 'com.gunha.lock.locks.StampedLockWrapperTest'
```

Windows에서는 `gradlew.bat`을 사용합니다. 결과 보고서는 `build/reports/tests/test/index.html`에 생깁니다.

## 구성

패키지마다 `package-info.java`에 그 패키지의 개요가 있습니다.

예제 클래스는 Java API 하나를 감싼 래퍼이고, 이름은 **감싼 API 이름 + `Wrapper`** 입니다(예: `Semaphore` → `SemaphoreWrapper`). 메서드 이름도 감싼 API의 메서드 이름을 따릅니다. 감싸는 API가 없는 반례 `UnsafeCounter`만 예외입니다.

```
src/main/java/com/gunha/lock
├── monitor/      JVM 내장 모니터 (synchronized, wait/notify)
├── locks/        java.util.concurrent.locks (명시적 락)
└── concurrent/   java.util.concurrent 동기화 도구 + 원자 변수
```

### monitor: JVM 내장 모니터

객체 헤더와 mark word의 비트 배치, 스택 락에서 팽창으로 넘어가는 과정, JDK 버전별 변화는 `monitor/package-info.java`에 그림으로 정리했습니다.

| 예제 | 다루는 내용 |
|---|---|
| `UnsafeCounter` | 락이 없을 때의 갱신 유실. `volatile`은 가시성만 주고 원자성은 주지 않음 |
| `SynchronizedWrapper` | `monitorenter`/`monitorexit`, 락 팽창, 재진입, 대기자가 `BLOCKED`가 되는 것 |
| `WaitNotifyWrapper` | `wait/notifyAll` 생산자-소비자, `while`로 조건을 다시 확인하는 이유, WaitSet이 하나뿐인 한계 |

### locks: 명시적 락

| 예제 | 다루는 내용 |
|---|---|
| `ReentrantLockWrapper` | AQS `state`와 CAS, 공정·비공정, `tryLock(timeout)`, `lockInterruptibly`, 대기자가 `WAITING`이 되는 것 |
| `ConditionWrapper` | 조건 큐 분리(`notFull`/`notEmpty`)로 `signal` 하나면 충분해지는 구조 |
| `ReentrantReadWriteLockWrapper` | `state` 상위·하위 16비트 분할, 공유 읽기, 다운그레이드는 되고 업그레이드는 안 되는 이유 |
| `StampedLockWrapper` | 낙관적 읽기와 `validate`, 재진입 불가, 소유자 없음, `tryConvertToWriteLock` |
| `LockSupportWrapper` | 모든 락의 바닥인 `LockSupport.park/unpark`, permit 모델 |

### concurrent: 동기화 도구와 원자 변수

| 예제 | 다루는 내용 |
|---|---|
| `SemaphoreWrapper` | 동시 실행 수 제한, AQS 공유 모드, 소유자 없음 |
| `CountDownLatchWrapper` | `CountDownLatch`로 시작 신호·완료 대기, 일회용, happens-before |
| `CyclicBarrierWrapper` | `CyclicBarrier` 라운드, barrier action, 깨진 배리어 |
| `AtomicIntegerWrapper` | CAS 재시도 루프와 `incrementAndGet`(`lock xadd`) 비교 |
| `LongAdderWrapper` | `LongAdder`의 셀 분산과 false sharing 방지 |

## 한눈에 비교

| | `synchronized` | `ReentrantLock` | `ReentrantReadWriteLock` | `StampedLock` | `Atomic*` |
|---|---|---|---|---|---|
| 구현 위치 | JVM (C++) | 자바 라이브러리 (AQS) | 자바 라이브러리 (AQS) | 자바 라이브러리 | CPU 원자 명령 |
| 대기 상태 | `BLOCKED` | `WAITING` | `WAITING` | `WAITING` | 대기 없음 |
| 재진입 | O | O | O | X | 해당 없음 |
| 시간 제한·인터럽트 | X | O | O | O | 해당 없음 |
| 공정성 선택 | X | O | O | X | 해당 없음 |
| 조건 대기 | `wait/notify` (1개) | `Condition` (여러 개) | 쓰기 락만 | X | X |
| 해제 | 자동 | `finally`에서 직접 | `finally`에서 직접 | stamp로 직접 | 해당 없음 |
| Java 21 가상 스레드 | 블로킹 시 pinning | 문제없음 | 문제없음 | 문제없음 | 문제없음 |

## 테스트 방식

동시성 테스트는 `sleep` 뒤에 결과를 보면 실행 환경에 따라 가끔 실패합니다. 그래서 테스트 도우미 `TestThreads`는 **상대 스레드가 원하는 상태(`BLOCKED`, `WAITING`, `TIMED_WAITING`)가 될 때까지 폴링**하는 방식을 씁니다.

```java
Started<Void> contender = TestThreads.start("contender", () -> {
    wrapper.incrementWithLock(); // ReentrantLockWrapper
    return null;
});
TestThreads.awaitState(contender.thread(), Thread.State.WAITING); // 지금 락을 기다리는 중임을 확인
```

여러 스레드로 같은 동작을 반복하는 테스트는 `TestThreads.runConcurrently`가 맡습니다. `ExecutorService`에 작업을 넣고 `CountDownLatch` 세 개를 씁니다. `ready`로 모든 작업이 출발선에 왔는지 확인하고, `startGate`로 동시에 출발시킨 뒤, `done`으로 모든 작업의 완료를 기다립니다. 작업에서 난 예외는 모두 모아 테스트 실패로 보고하고, 시간 초과도 실패로 처리합니다. `countDown()` → `await()`의 happens-before 덕분에 작업 결과가 테스트 스레드에 보입니다.

모든 테스트 클래스에는 `@Timeout`이 붙어 있어서, 교착이 생기면 멈추지 않고 실패로 끝납니다.

## 직접 확인해 볼 것

- **바이트코드:** `./gradlew compileJava` 후 `javap -c -v build/classes/java/main/com/gunha/lock/monitor/SynchronizedWrapper.class`를 실행하면 `monitorenter`/`monitorexit`과 `ACC_SYNCHRONIZED`가 보입니다.
- **스레드 덤프:** 테스트 실행 중 `jstack <pid>`로 `waiting to lock <...>`(모니터)와 `parking to wait for <...>`(AQS)의 차이를 볼 수 있습니다.
- **CAS 경합:** `AtomicIntegerWrapperTest` 출력에서 CAS 실패 후 재시도 횟수를 볼 수 있습니다.
