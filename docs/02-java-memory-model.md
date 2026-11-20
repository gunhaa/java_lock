# 2. 자바 메모리 모델 (JMM)

락이 하는 일은 두 가지입니다.

1. **원자성:** 한 번에 한 스레드만 임계 구역을 실행하게 합니다.
2. **가시성과 순서:** 한 스레드가 쓴 값을 다른 스레드가 **언제, 어떤 순서로** 보는지 정합니다.

1번은 직관적이지만, 2번은 직관과 다르게 동작합니다. 2번의 규칙을 정한 것이 자바 메모리 모델(Java Memory Model, JMM)입니다. JLS 17장에 정의되어 있고, Java 5(JSR-133)에서 지금 형태가 되었습니다.

## 2.1 왜 필요한가: 코드는 쓴 순서대로 실행되지 않는다

단일 스레드에서는 결과만 같으면 어떤 최적화든 허용됩니다. 그 최적화가 다른 스레드에게는 보입니다.

### 재정렬 (reordering)

```java
int a = 0, b = 0;   // 공유 필드
int r1, r2;

// 스레드 1         // 스레드 2
a = 1;              b = 1;
r1 = b;             r2 = a;
```

직관적으로는 어떤 순서로 섞여도 `r1`과 `r2` 중 최소 하나는 1이어야 합니다. 그런데 실제로는 **`r1 == 0 && r2 == 0`이 나올 수 있습니다.** 각 스레드 안에서 두 줄은 서로 의존하지 않으니, 다음 세 곳에서 순서가 바뀔 수 있습니다.

| 누가 | 어떻게 |
|---|---|
| javac / JIT 컴파일러 | 더 빠른 코드를 위해 명령 순서를 바꿈 |
| CPU | 비순차 실행(out-of-order execution) |
| 메모리 시스템 | 쓰기가 **스토어 버퍼**에 잠시 머물러 다른 코어에 늦게 보임. x86에서도 "쓰기 뒤의 읽기"는 앞당겨질 수 있음 ([3장](03-hardware-and-os.md#33-스토어-버퍼와-재정렬)) |

### 가시성 (visibility)

```java
boolean running = true;   // volatile 아님

// 스레드 1                    // 스레드 2
while (running) { }            running = false;
```

스레드 2가 `false`를 써도 스레드 1이 **영원히 멈추지 않을 수 있습니다.** JIT 입장에서는 루프 안에서 `running`을 바꾸는 코드가 없습니다. 그래서 값을 한 번만 읽어 레지스터에 두거나, 아예 `if (running) while (true) { }`로 바꿔도 단일 스레드 의미로는 올바릅니다(hoisting).

JMM은 이런 최적화를 **어디까지 허용하고, 프로그래머가 무엇을 하면 막을 수 있는지** 정합니다.

## 2.2 핵심 개념: happens-before <a id="happens-before"></a>

JMM의 중심은 **happens-before(hb)** 관계입니다.

> 동작 A가 동작 B보다 happens-before이면, A의 결과(메모리 쓰기)는 B에게 반드시 보이고, A는 B보다 먼저 일어난 것처럼 보인다.

"시간상 먼저 실행됨"과는 다른 말입니다. 시간상 먼저 썼어도 hb 관계가 없으면 다른 스레드가 그 값을 본다는 보장이 없습니다. 반대로 hb 관계가 있으면, 실제 실행 순서와 상관없이 **그렇게 보이도록** JVM이 보장합니다.

### happens-before 규칙 (JLS 17.4.5)

| 규칙 | 내용 | 예제 |
|---|---|---|
| **프로그램 순서** | 한 스레드 안에서는 앞의 동작이 뒤의 동작보다 hb | 모든 코드 |
| **모니터 락** | 모니터 해제(unlock)는 **같은 모니터**의 이후 획득(lock)보다 hb | `SynchronizedWrapper` |
| **volatile** | volatile 변수 쓰기는 **같은 변수**의 이후 읽기보다 hb | `LockSupportWrapper` |
| **스레드 시작** | `t.start()`는 t 안의 모든 동작보다 hb | |
| **스레드 종료** | t 안의 모든 동작은 다른 스레드에서 `t.join()`이 돌아오는 것(또는 `t.isAlive()`가 false를 보는 것)보다 hb | |
| **인터럽트** | `t.interrupt()`는 t가 인터럽트를 감지하는 것보다 hb | |
| **이행성** | A hb B이고 B hb C이면 A hb C | 아래 예시 |

이행성 덕분에 **volatile 변수 하나로 그 앞의 일반 쓰기까지 함께 보이게** 할 수 있습니다.

```java
int data;                    // 일반 필드
volatile boolean ready;

// 스레드 1                   // 스레드 2
data = 42;        // (1)      if (ready) {          // (3)
ready = true;     // (2)          print(data);      // (4) 반드시 42
                              }
```

(1) hb (2)는 프로그램 순서, (2) hb (3)은 volatile 규칙, (3) hb (4)는 프로그램 순서입니다. 이행성으로 (1) hb (4)가 되므로 `data`는 volatile이 아니어도 42로 보입니다.

### `java.util.concurrent`가 추가로 보장하는 것

JDK의 동시성 도구들은 javadoc의 "Memory Consistency Effects"에 각자의 hb 규칙을 명시합니다. 내부적으로는 volatile과 CAS로 위 규칙을 이어 붙여 만든 것입니다.

| 도구 | hb 관계 | 이 프로젝트 |
|---|---|---|
| `Lock` | `unlock()` → 이후 `lock()` (모니터와 같음) | `ReentrantLockWrapper` |
| `Condition` | `signal()` 전의 동작 → 깨어난 `await()` 이후 | `ConditionWrapper` |
| `Semaphore` | `release()` 전의 동작 → 그 허가로 성공한 `acquire()` 이후 | `SemaphoreWrapper` |
| `CountDownLatch` | `countDown()` 전의 동작 → 그 덕분에 돌아온 `await()` 이후 | `CountDownLatchWrapper`, `TestThreads.runConcurrently` |
| `CyclicBarrier` | 각 스레드의 `await()` 전 → barrier action → 다른 스레드들의 `await()` 이후 | `CyclicBarrierWrapper` |
| `Executor` / `Future` | `execute()`·`submit()` 전 → 작업 실행, 작업 실행 → `Future.get()` 이후 | `TestThreads.runConcurrently`(제출), `TestThreads.Started.join()`(결과) |
| 동시성 컬렉션 | 넣기 전의 동작 → 그 원소를 꺼낸 이후 | `TestThreads.runConcurrently`의 예외 수집 큐 |
| `Atomic*` | `set`/`compareAndSet`은 volatile 쓰기, `get`은 volatile 읽기와 같음 | `AtomicIntegerWrapper` |

`CountDownLatchWrapperTest`가 volatile이 아닌 `int[]` 배열로 결과를 받아도 되는 이유가 이 표의 `CountDownLatch` 줄입니다.

## 2.3 데이터 경쟁과 DRF-SC 보장

- **데이터 경쟁(data race):** 두 스레드가 같은 변수에 접근하고, 그중 하나 이상이 쓰기이고, 둘 사이에 hb 관계가 없는 경우입니다.
- **올바르게 동기화된 프로그램:** 가능한 모든 실행에서 데이터 경쟁이 없는 프로그램입니다.

JMM의 가장 중요한 약속은 이것입니다.

> **데이터 경쟁이 없는 프로그램은 순차 일관적(sequentially consistent)으로 동작한다.** (DRF-SC: data-race-free → sequential consistency)

순차 일관성이란 "모든 스레드의 동작을 어떤 한 줄로 늘어놓은 것처럼, 각 스레드 안에서는 코드 순서대로 실행된 것처럼" 보인다는 뜻입니다. 즉 **공유 변수 접근을 전부 락이나 volatile 등으로 hb 관계에 묶어 두면, 재정렬·캐시·스토어 버퍼 같은 2.1의 문제를 전혀 신경 쓰지 않아도 됩니다.** 락을 쓰는 진짜 이유가 여기 있습니다.

데이터 경쟁이 있는 프로그램도 C/C++처럼 "정의되지 않은 동작"이 되지는 않습니다. JMM은 어떤 스레드도 쓰지 않은 값이 갑자기 나타나는 것(out-of-thin-air)은 금지합니다. 하지만 위의 `r1 == r2 == 0` 같은 직관에 반하는 결과는 허용됩니다.

## 2.4 원자성: 무엇이 쪼개지지 않는가

| 동작 | 원자적인가 |
|---|---|
| `int`, `short`, `byte`, `char`, `boolean`, `float`, 참조의 읽기·쓰기 | O |
| `long`, `double`의 읽기·쓰기 | **volatile이면 O.** 일반 필드면 명세상 32비트 두 번으로 쪼개질 수 있음(JLS 17.7). 64비트 JVM에서는 실제로 원자적이지만 명세가 보장하지는 않음 |
| `count++`, `x += 1`, `if (x == null) x = ...` | **X.** 읽기-수정-쓰기 또는 확인-후-실행(check-then-act)은 여러 동작이다 |

`volatile`은 각 읽기·쓰기를 원자적이고 보이게 만들 뿐, **여러 동작을 묶어 주지는 않습니다.** `UnsafeCounter`가 보여 주는 내용입니다. 여러 동작을 하나로 묶으려면 락이나 CAS(`Atomic*`)가 필요합니다.

## 2.5 volatile 정리 <a id="volatile"></a>

| 보장 | 설명 |
|---|---|
| 가시성 | 쓰기는 이후의 모든 읽기에 보인다. JIT이 레지스터에 캐시하거나 루프 밖으로 끌어내지 못한다 |
| 순서 | volatile 쓰기 **앞**의 동작은 그 뒤로 내려가지 않는다(release). volatile 읽기 **뒤**의 동작은 그 앞으로 올라오지 않는다(acquire) |
| 전체 순서 | 모든 volatile 접근은 모든 스레드가 동의하는 하나의 순서를 가진다(순차 일관) |
| 원자성 | 단일 읽기·쓰기만. `count++`은 보장하지 않음 |

**쓰기 좋은 곳:** 한 스레드만 쓰고 여러 스레드가 읽는 상태 플래그(`running`, `LockSupportWrapper.signaled`), 불변 객체 참조의 발행(publication).
**쓰면 안 되는 곳:** 현재 값을 바탕으로 새 값을 계산하는 갱신(카운터, 잔액). 이때는 락이나 `Atomic*`을 씁니다.

## 2.6 final 필드와 안전한 발행

### final 필드의 특별한 보장

생성자가 끝날 때 final 필드의 값이 "얼어붙습니다(freeze)". 객체가 **올바르게 생성되었다면**(생성자 안에서 `this`가 다른 스레드로 새어 나가지 않았다면), 다른 스레드는 그 객체의 참조를 데이터 경쟁으로 얻었더라도 **final 필드만큼은 초기화된 값으로 봅니다.** final이 아닌 필드에는 이 보장이 없습니다.

불변 객체(모든 필드가 final인 객체)가 동기화 없이 공유해도 안전한 이유가 이것입니다.

### 안전하지 않은 발행의 예: volatile 없는 이중 확인 잠금

```java
class Helper {
    int value;
    Helper() { value = 42; }
}

private static Helper instance;      // volatile이 없음 → 버그

static Helper getInstance() {
    if (instance == null) {                  // (1) 락 없이 확인
        synchronized (Helper.class) {
            if (instance == null) {
                instance = new Helper();     // (2)
            }
        }
    }
    return instance;
}
```

#### (2)는 세 단계다

```
new           Helper          // ① 힙에 메모리 할당 + 객체 헤더 설정 + 모든 필드를 기본값(0, null, false)으로 채움
dup
invokespecial Helper.<init>   // ② 생성자 실행: this.value = 42
putstatic     instance        // ③ 객체 참조(주소)를 static 필드 instance에 저장
```

- **①이 끝나면 객체는 이미 힙에 있습니다.** `value`가 0인 완전한 객체이고, JVM에는 "생성 중"이라는 상태가 따로 없습니다. 생성자(②)는 이미 있는 객체의 필드에 값을 써 넣는 메서드 호출일 뿐입니다.
- **`null`은 객체의 상태가 아니라 `instance` 변수의 값입니다.** ③에서 주소가 저장되기 전까지만 `null`이고, 그 뒤로는 생성자가 끝났든 아니든 `null`이 아닙니다.

#### ②와 ③의 순서는 보장되지 않는다

②의 `value = 42`와 ③의 `instance = 주소`는 서로 다른 메모리 위치에 대한 독립된 쓰기입니다. 스레드 A 혼자 보면 어느 쪽이 먼저든 결과가 같으므로, JIT은 생성자를 인라인한 뒤 순서를 바꿀 수 있습니다. ARM 같은 약한 메모리 모델의 CPU는 코드 순서대로 실행해도 다른 코어에 ③을 먼저 보여 줄 수 있습니다([3.3절](03-hardware-and-os.md#33-스토어-버퍼와-재정렬)).

| 시점 | 스레드 A (락 안에서 생성) | 스레드 B (락 없이 `getInstance()`) |
|---|---|---|
| 1 | ① 할당: 힙에 객체 생김, `value = 0` | |
| 2 | ③이 먼저 보임: `instance = 주소` | |
| 3 | | (1) `instance == null`? → **아님** → 락을 건너뜀 |
| 4 | | 받은 객체의 `value`를 읽음 → **0** |
| 5 | ② `value = 42`가 이제야 보임 | |

B는 `null`도 쓰레기 값도 보지 않았습니다. **이미 힙에 있는 진짜 객체**를 받았는데, 그 필드가 생성자가 쓰기 전의 기본값으로 보인 것입니다. 이것이 "초기화가 덜 된 객체"의 정확한 의미입니다.

- **B만 문제인 이유:** B가 락 안의 두 번째 `if`까지 들어갔다면, A의 락 해제 → B의 락 획득 사이의 hb 덕분에 ②, ③이 모두 보입니다. 버그는 락 밖의 첫 번째 `if` (1)에서 납니다. 이 읽기는 A의 쓰기와 hb 관계가 없어서, "③은 보이고 ②는 안 보이는" 조합이 허용됩니다.
- **최악이어도 기본값:** JMM은 기본값 쓰기가 모든 스레드의 첫 동작보다 먼저라고 보장합니다(JLS 17.4.4). HotSpot도 헤더 설정과 0 채우기가 끝난 뒤에야 참조가 밖으로 나가게 합니다. 그래서 이전에 그 메모리에 있던 엉뚱한 값은 보이지 않습니다.
- **재현이 어렵다:** x86(TSO)은 하드웨어가 쓰기끼리의 순서를 지켜 주므로 원인이 JIT 재정렬뿐이라 거의 보이지 않습니다. ARM에서는 하드웨어 수준에서도 일어납니다. 확인하려면 jcstress 같은 동시성 스트레스 도구가 필요합니다.

#### 고치는 방법

1. **`instance`를 `volatile`로:** ③이 release 쓰기가 되어 앞의 ②가 뒤로 넘어가지 못하고, (1)이 acquire 읽기가 되어 뒤의 `value` 읽기가 앞으로 당겨지지 못합니다. hb로 보면 ② → ③(프로그램 순서) → B의 (1)(volatile 규칙) → B의 `value` 읽기(프로그램 순서)가 이행성으로 이어집니다.
2. **`value`를 `final`로:** 생성자가 끝날 때 final 필드가 고정되어서, 참조를 경쟁으로 얻어도 final 필드는 42로 보입니다. final이 아닌 필드에는 효과가 없습니다.
3. **홀더 클래스:** 클래스 초기화는 JVM이 락으로 보호하므로 volatile 없이도 안전한 지연 초기화가 됩니다.
   ```java
   private static class Holder {
       static final Helper INSTANCE = new Helper();   // Holder가 처음 쓰일 때 한 번만 초기화
   }
   static Helper getInstance() { return Holder.INSTANCE; }
   ```

### 안전하게 발행하는 방법

객체를 다른 스레드에 넘길 때 다음 중 하나를 쓰면 hb 관계가 생깁니다.

1. static 초기화(클래스 초기화는 JVM이 락으로 보호함)
2. `volatile` 필드나 `AtomicReference`에 저장
3. final 필드에 저장
4. 락으로 보호되는 필드에 저장
5. 동시성 컬렉션(`ConcurrentHashMap`, `BlockingQueue` 등)에 넣기

## 2.7 JVM은 어떻게 지키나: 메모리 배리어

JMM은 규칙만 정하고, JIT 컴파일러가 CPU마다 알맞은 명령을 넣어 지킵니다. 개념적으로 네 종류의 배리어가 있습니다(Doug Lea, *The JSR-133 Cookbook for Compiler Writers*).

| 배리어 | 막는 재정렬 |
|---|---|
| LoadLoad | 앞의 읽기와 뒤의 읽기 |
| LoadStore | 앞의 읽기와 뒤의 쓰기 |
| StoreStore | 앞의 쓰기와 뒤의 쓰기 |
| StoreLoad | 앞의 쓰기와 뒤의 읽기 (가장 비쌈) |

CPU마다 기본으로 허용하는 재정렬이 달라서, 실제로 들어가는 명령도 다릅니다.

| 동작 | x86-64 (TSO: StoreLoad만 재정렬됨) | ARM64 (약한 모델) |
|---|---|---|
| volatile 읽기 | 일반 `mov` (추가 비용 없음) | `ldar` (acquire 읽기) |
| volatile 쓰기 | `mov` + `lock addl $0,(rsp)` (StoreLoad 배리어) | `stlr` (release 쓰기) |
| CAS | `lock cmpxchg` (그 자체로 full fence) | `casal` 또는 `ldaxr`/`stlxr` 루프 |
| 모니터 진입·해제 | CAS 기반 (위와 같음) | 위와 같음 |

x86에서는 volatile 읽기가 공짜이고 쓰기만 비쌉니다. ARM에서는 둘 다 일반 접근보다 비쌉니다. **같은 자바 코드도 CPU에 따라 데이터 경쟁 버그가 드러나는 정도가 다릅니다.** x86 개발 PC에서는 멀쩡하던 코드가 ARM 서버에서 깨지는 일이 실제로 있습니다.

## 2.8 VarHandle 접근 모드 (Java 9+)

`VarHandle`은 volatile보다 약한 순서 보장을 골라 쓸 수 있게 해 줍니다. `Atomic*`과 AQS가 내부에서 씁니다. 직접 쓸 일은 드물지만 JDK 소스를 읽을 때 나옵니다.

| 모드 | 보장 | 대응 |
|---|---|---|
| Plain | 아무 보장 없음 | 일반 필드 접근 |
| Opaque | 그 변수 하나에 대해서만 순서 보장, 결국은 보임 | |
| Acquire / Release | 읽기 뒤가 앞으로, 쓰기 앞이 뒤로 넘어가지 않음 | `getAcquire` / `setRelease` |
| Volatile | 순차 일관 | `volatile` 필드 |

예를 들어 `StampedLock.validate()`는 `VarHandle.acquireFence()`로 "앞에서 읽은 필드 값이 stamp 확인보다 뒤로 밀리지 않게" 합니다.

## 2.9 이 프로젝트의 예제와 연결

| 예제 | 어떤 JMM 규칙에 기대는가 |
|---|---|
| `UnsafeCounter` | volatile은 가시성만 주고 `count++`의 원자성은 주지 않음 (2.4, 2.5) |
| `SynchronizedWrapper` | 모니터 락 규칙. `count`가 volatile이 아니어도 됨 (2.2) |
| `WaitNotifyWrapper`, `ConditionWrapper` | 대기에서 깨어날 때 락을 다시 얻으므로 락 규칙이 적용됨 |
| `ReentrantLockWrapper` | `Lock`의 hb 보장. 내부적으로는 AQS `state`의 volatile 쓰기·읽기 |
| `StampedLockWrapper` | 낙관적 읽기는 hb가 없는 읽기다. 그래서 `validate`로 사후 확인이 필요함 (2.8) |
| `LockSupportWrapper` | volatile 쓰기 → 읽기 규칙과 이행성 |
| `CountDownLatchWrapper`, `CyclicBarrierWrapper` | `CountDownLatch`, `CyclicBarrier`의 hb 보장으로 일반 배열을 공유 |
| `AtomicIntegerWrapper`, `LongAdderWrapper` | `Atomic*`의 volatile 의미 + CAS의 원자성 |

## 더 읽을거리

- JLS 17장 *Threads and Locks* (특히 17.4 Memory Model)
- Brian Goetz 외, *Java Concurrency in Practice* 3장(Sharing Objects), 16장(The Java Memory Model)
- Doug Lea, *The JSR-133 Cookbook for Compiler Writers*
- Aleksey Shipilёv, *Java Memory Model Pragmatics*
- `java.util.concurrent` 패키지 javadoc의 *Memory Consistency Properties*
