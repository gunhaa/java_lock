# 선행 지식

예제 코드를 읽기 전에 알아 두면 좋은 자바와 하드웨어의 기본 모델을 정리했습니다. 예제 주석에 나오는 용어(happens-before, `BLOCKED`/`WAITING`, 캐시 라인, futex 등)는 모두 여기서 설명합니다.

## 읽는 순서

| 문서 | 내용 | 먼저 읽으면 좋은 예제 |
|---|---|---|
| [1. 자바 스레드 모델](01-thread-model.md) | 플랫폼·가상 스레드, OS 스레드와의 관계, 컨텍스트 스위치, 스레드 상태, 인터럽트, 데몬, `ExecutorService` | 모든 테스트 (`TestThreads`) |
| [2. 자바 메모리 모델 (JMM)](02-java-memory-model.md) | 재정렬과 가시성 문제, happens-before 규칙, 데이터 경쟁, 원자성, volatile, final과 안전한 발행, 메모리 배리어 | `monitor.UnsafeCounter`, `monitor.SynchronizedCounter` |
| [3. 하드웨어와 OS](03-hardware-and-os.md) | 메모리 계층, 캐시 라인과 MESI, 스토어 버퍼, 배리어, 원자 명령, 스핀과 블로킹, futex | `concurrent.CasCounter`, `concurrent.HitCounter` |

## 한 장 요약

1. **스레드는 힙을 공유합니다.** 공유 필드를 여러 스레드가 다루면 문제가 생깁니다. ([1장](01-thread-model.md))
2. **문제는 두 가지입니다.**
   - 원자성: `count++` 같은 여러 동작 사이에 다른 스레드가 끼어듭니다.
   - 가시성과 순서: 컴파일러, CPU, 스토어 버퍼 때문에 다른 스레드의 쓰기가 늦게 보이거나 순서가 바뀌어 보입니다. ([2장](02-java-memory-model.md), [3장](03-hardware-and-os.md))
3. **JMM의 약속:** 공유 변수 접근을 모두 happens-before 관계로 묶으면(데이터 경쟁이 없으면) 프로그램은 순차적으로 실행된 것처럼 동작합니다.
4. **락은 두 문제를 함께 해결합니다.** 상호 배제로 원자성을 주고, 해제 → 획득 사이의 happens-before로 가시성을 줍니다.
5. **락의 비용은 경합에서 나옵니다.** 경합이 없으면 CAS 한 번이고, 경합이 있으면 캐시 라인 이동과 스핀, 그래도 안 되면 OS 대기와 컨텍스트 스위치가 붙습니다.
