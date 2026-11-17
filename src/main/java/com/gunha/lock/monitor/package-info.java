/**
 * 모니터 락: JVM에 내장된 락. ({@code synchronized}, {@code Object.wait/notify})
 *
 * <h2>무엇인가</h2>
 * 모든 자바 객체는 락으로 쓸 수 있다. 락 상태는 객체 헤더의 mark word에 기록되고,
 * 경합이 생기면 HotSpot이 C++ 객체인 {@code ObjectMonitor}를 붙인다(inflation).
 * 락 획득·해제·대기를 전부 JVM이 처리하므로 라이브러리 코드가 아니라 바이트코드 명령과 VM 런타임의 영역이다.
 *
 * <h2>JVM 관점</h2>
 * <ul>
 * <li>블록: {@code monitorenter}/{@code monitorexit} 바이트코드. 메서드: {@code ACC_SYNCHRONIZED} 플래그.</li>
 * <li>경합이 없으면 CAS 한 번으로 끝난다(OS 호출 없음). 경합이 생기면 잠깐 스핀하고, 그래도 안 되면 OS에 재워 달라고 한다.</li>
 * <li>락 진입 대기 = {@code BLOCKED}, {@code wait()} 대기 = {@code WAITING}/{@code TIMED_WAITING}.</li>
 * <li>해제 → 같은 모니터의 이후 획득 사이에 happens-before가 성립한다(JLS 17.4.5). 가시성까지 함께 보장된다.</li>
 * </ul>
 *
 * <h2>OS 관점</h2>
 * 자바 플랫폼 스레드는 OS 스레드와 1:1이다. 모니터에서 잠드는 스레드는 HotSpot의 {@code ParkEvent}를 통해
 * Linux에서는 {@code pthread_cond_wait}(내부적으로 futex 시스템 콜), Windows에서는 이벤트 객체에 대한
 * {@code WaitForSingleObject}로 커널에 들어가 스케줄러 대기열에서 빠진다. 깨어날 때 컨텍스트 스위치 비용이 든다.
 *
 * <h2>예제</h2>
 * <ul>
 * <li>{@link com.gunha.lock.monitor.UnsafeCounter}: 락이 없을 때 생기는 갱신 유실(volatile로도 못 막음)</li>
 * <li>{@link com.gunha.lock.monitor.SynchronizedCounter}: {@code synchronized}의 상호 배제, 재진입, BLOCKED 상태</li>
 * <li>{@link com.gunha.lock.monitor.MonitorBoundedBuffer}: {@code wait/notifyAll}로 만든 생산자-소비자</li>
 * </ul>
 */
package com.gunha.lock.monitor;
