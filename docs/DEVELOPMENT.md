# 개발 문서

소스를 직접 빌드하거나 내부 동작을 알고 싶은 분을 위한 문서예요. 설치와 사용법은 [README](../README.md)에 있어요.

대상은 리디 페이퍼 프로, Android 4.4.2(Dalvik), 리디 앱 `com.ridi.paper` **P213_1.6.5 (versionCode 2024081601)**이에요. 모듈은 이 버전이 아니면 후킹을 걸지 않아요.

## 구조

| 파일 | 역할 |
| --- | --- |
| `src/.../Hook.java` | Xposed 진입점이에요. 리디 앱 프로세스에서만 후킹을 걸고, 나머지 코드는 리디 앱의 클래스 로더를 부모로 하는 별도 로더로 불러와요. |
| `src/.../Integration.java` | WebDAV 탭 화면, 다운로드 대기열, 시리즈 묶음, 설정 항목이에요. |
| `src/.../DavClient.java` | WebDAV 목록 조회(PROPFIND)와 다운로드예요. 안드로이드에 의존하지 않아서 PC에서 시험해요. |
| `src/.../Epub.java` | 리디 파서가 읽지 않는 EPUB 정보(시리즈 순번, 표지 후보)를 읽어요. |
| `src/.../BooksAdapter.java` | 리디의 책장 어댑터를 상속해 폴더 목록을 책장처럼 보여 줘요. |
| `stubs/` | 리디 앱 클래스의 컴파일용 선언이에요. APK에는 들어가지 않아요. |
| `lib/` | 컴파일과 PC 시험에 쓰는 라이브러리예요. APK에는 들어가지 않아요. 출처와 라이선스는 [THIRD_PARTY_NOTICES](../THIRD_PARTY_NOTICES.md)에 있어요. |
| `tests/` | PC용 시험(`DavClientTest`)과 기기용 API 검사(`RuntimeCheck`)예요. |

리디 앱에 이미 들어 있는 **OkHttp 3.12.13 / Okio**를 그대로 써요. 모듈 APK에는 이 라이브러리도, 리디 앱의 코드도 넣지 않아요.

## 리디 앱에서 빌려 쓰는 것

| 기능 | 방법 |
| --- | --- |
| 상단 WebDAV 탭 | 기존 `UnderlineTabTitleTextView`와 메뉴 레이아웃을 써요. |
| 폴더의 책 목록 | 기존 `ShelfView`, `i0` 어댑터, `ShelfGridBookView`, 표지 그리기를 써요. |
| 책 가져오기 · 열기 | 기존 가져오기 작업 `d.k.a.c.e0.w1.n.h`를 다운로드 스레드에서 직접 실행하고, `BookOpenerActivity`로 열어요. |
| 다운로드 표시 | 표지 위의 기존 `BookDownloadProgressView`(대기 · 진행률 · 실패)를 WebDAV 대기열 상태로 움직여요. |
| 전체 다운로드 | 폴더 도구줄의 글자 버튼이에요. 페이퍼 테마의 기존 버튼도 글자만 보여 주기 때문에 같은 모양으로 했어요. |
| 시리즈 묶음 | 구매한 시리즈와 같은 `BookGroup` 레코드를 만들어요(키 `webdav:<폴더 URL SHA-256>`). 책장에는 마지막 권만 남아요. |
| 설정 항목 | 탭이 바뀔 때 호출되는 `SettingsFragmentPaper.G()` 뒤에서, 파일 관리 탭 위에 `WebDAV 설정` 행을, 전원/보안 목록 끝에 재부팅 항목을 붙여요. |
| 재부팅 | 리디 앱이 시스템 UID(REBOOT 권한)라서 `PowerManager.reboot("recovery" / "fastboot")`를 직접 불러요. 이 기기는 `adb reboot fastboot`와 같은 사유 `fastboot`로 fastboot에 들어가요. |

### EPUB 파서 보정

- **제목**: 리디 파서는 OPF의 마지막 `<dc:title>`을 제목으로 써요. 책장 이름 같은 collection 제목이 뒤에 붙은 파일에서는 엉뚱한 제목이 되기 때문에, 첫 제목을 유지하도록 `pushTitle`을 후킹했어요.
- **표지**: 리디 파서는 EPUB 2 방식 `<meta name="cover">`의 id만 따라가요. 그 값이 없거나 틀리면 매니페스트의 `properties="cover-image"` 항목(EPUB 3)을, 그것도 없으면 이름이 `cover`인 이미지, 그다음 id나 경로에 `cover`가 들어간 이미지를 써요(`Epub.coverRank`).
- 보정 전에 가져온 책은 첫 실행 때 한 번 자동으로 고쳐요.

### 시리즈 묶음 기준

- **같은 서버 폴더의 책 = 한 시리즈**예요. 묶음 제목은 폴더 이름이고, 서버 구조에 대한 다른 가정은 없어요.
- 그 폴더에서 받은 책이 2권 이상일 때 묶어요.
- 권 순서는 EPUB의 표준 시리즈 순번(EPUB 3 `group-position`, 없으면 `calibre:series_index`)을 먼저 쓰고, 없으면 파일 이름 순서예요. 이름은 숫자를 수로 비교해요(`DavClient.natural`). 폴더 목록도 같은 순서로 보여 줘요.
- 책을 받을 때와 폴더를 열 때 적용해요.
- 묶음이 이미 책장에 있으면 새 권이 그 자리를 이어받아요. 사용자가 옮겨 둔 책장을 유지하기 위해서예요.

## 연결과 보관 정책

- HTTPS(TLS 1.2)를 기본으로 하고, 기본 인증(Basic) 또는 익명 접속만 지원해요. Digest · OAuth · 클라이언트 인증서는 지원하지 않아요.
- 평문 HTTP는 서버 주소가 사설 · 루프백 · 링크 로컬 주소로 풀릴 때만 연결해요(`DavClient.local`). OkHttp의 DNS 단계에서 걸러서, 바깥 주소로는 요청이 나가지 않아요.
- HTTPS는 기기의 신뢰 저장소로 인증서와 호스트 이름을 검증하고, 검증을 건너뛰는 설정은 없어요.
- 요청은 모두 하나의 OkHttp 클라이언트를 같이 써요. 연결과 TLS 세션을 재사용해서, 폴더를 옮길 때마다 TLS를 다시 맺지 않아요.
- 리디렉션은 따라가지 않아요. 인증 정보가 다른 주소로 넘어가는 것을 막기 위해서예요.
- 다른 서버, 설정한 루트 밖, 바로 아래 단계가 아닌 항목은 목록에서 빼요. XML의 DTD와 외부 엔티티는 거절해요.
- 폴더 응답은 최대 4 MiB, 5,000항목이에요. 넘으면 일부만 보여 주지 않고 오류를 표시해요.
- 비밀번호는 Android 키 저장소의 RSA 키(`paper-webdav`, 꺼낼 수 없는 개인 키)로 암호화해서 리디 앱의 `MODE_PRIVATE` 설정에 보관해요. 설정 파일만으로는 알 수 없지만, 루트 권한이나 같은 시스템 UID를 가진 쪽은 키 저장소에 복호화를 시킬 수 있어요. 복호화는 앱이 시작될 때 뒤에서 한 번 하고 메모리에만 둬요.
- 다운로드는 리디의 책 저장 위치(내부 저장소 또는 SD 카드) 안의 `.webdav/<URL SHA-256>/파일이름.part`에 쓰고, 끝나면 이름을 바꿔요. 같은 볼륨이라서 가져올 때 파일을 복사하지 않고 옮겨요(`d.g.a.c.e.l.m.x` 후킹). 남은 파일은 다음 실행 때 지워요.
- 가져온 뒤 URL과 리디 책 ID를 연결해 둬요. 서버 파일이 바뀌어도 자동으로 다시 받지는 않아요.
- 다운로드는 한 번에 한 권씩 처리해요. 대기열은 화면(액티비티)에 묶여 있어서 앱이 꺼지면 사라져요.
- 서버에는 PROPFIND와 GET만 보내요.

## 소스 받기

확장을 설치만 할 거라면 소스는 필요 없어요. 직접 빌드하거나 고쳐 보고 싶을 때만 받으면 돼요. 방법은 두 가지예요.

**ZIP으로 받기** (Git을 몰라도 돼요)

1. [저장소 첫 화면](https://github.com/Parkolando/PaperProWebDAV)에서 초록색 **[Code]** 버튼을 누르고 **[Download ZIP]**을 눌러요.
2. `다운로드` 폴더의 `PaperProWebDAV-main.zip`을 오른쪽 클릭하고 [압축 풀기...]를 눌러 `C:\`에 풀어요. `C:\PaperProWebDAV-main` 폴더가 생겨요.

**Git으로 받기** (나중에 최신 내용으로 갱신하기 쉬워요)

1. [Git for Windows](https://git-scm.com/download/win)를 설치해요. 설치 중에 나오는 선택지는 기본값 그대로 두면 돼요.
2. 시작 버튼을 오른쪽 클릭하고 [터미널]을 눌러요. 열린 창에 아래 명령을 한 줄씩 입력해요.
   ```powershell
   cd C:\
   git clone https://github.com/Parkolando/PaperProWebDAV.git
   cd PaperProWebDAV
   ```
   `C:\PaperProWebDAV` 폴더가 생기고, 창은 그 폴더 안으로 들어간 상태가 돼요. 나중에 최신 내용을 받으려면 이 폴더에서 `git pull`을 입력해요.

아래의 빌드 명령은 모두 이렇게 받은 **소스 폴더 안에서** 실행해요. 파일 탐색기에서 그 폴더를 열고, 빈 곳을 오른쪽 클릭해서 [터미널에서 열기]를 누르면 그 폴더에서 터미널이 열려요.

## 빌드와 검사

JDK 17, Android SDK(platform 33, build-tools 30.0.3), Python, adb가 필요해요.

```powershell
.\build.ps1           # build/paper-webdav.apk
.\test.ps1            # PC 시험: WebDAV 프로토콜, 이름 정렬, EPUB 정보
.\runtime-check.ps1   # 기기 검사. 기기가 여러 대면 -Serial <시리얼>
```

- `build.ps1`은 서명 키(`local-signing-key.p12`)가 없으면 새로 만들어요. 키가 바뀌면 이미 설치한 APK 위에 덮어 설치할 수 없으니, 배포용 키는 따로 잘 보관해 주세요.
- `runtime-check.ps1`은 모듈이 이름으로 찾아 쓰는 리디 앱의 클래스 · 메서드 · 필드가 실제로 있는지 기기에서 확인해요. 난독화된 이름을 쓰기 때문에, 새 이름을 쓰기 시작할 때마다 여기에 검사를 추가해 주세요. 기기에서 꺼낸 리디 APK가 `original/com.ridi.paper.apk`에 있어야 하고, 이 파일은 저장소에 올리지 않아요.

기기에 넣을 때는 덮어 설치하고 재부팅해요. Xposed Installer가 모듈 목록을 스스로 고쳐 줘요.

```powershell
adb install -r build/paper-webdav.apk
adb reboot
adb logcat -d | Select-String PaperWebDAV   # "hooks ready"가 보이면 정상이에요
```

## 확인한 것과 못 한 것

- 기기에서 확인했어요: 내부망 HTTP 연결, 암호화된 비밀번호로 접속, 받은 파일을 복사 없이 옮기기, 폴더 이동, 책별 다운로드 표시, 전체 다운로드, 받는 책 취소, 받은 뒤 자동 열기, EPUB 제목 · 표지 보정, 시리즈 묶음(기존 책 소급, 새 권 합류, 묶음 펼치기, 묶음 안의 책 열기), 설정 항목 위치, fastboot 재부팅과 복귀.
- 아직 확인하지 못했어요: 저장 공간 부족과 네트워크 끊김 표시, `전체 취소` 버튼, 묶음 안의 책을 지웠을 때의 정리, 메타데이터가 없는 CBZ 시리즈의 묶음, 리커버리 재부팅.

## 참고

- 시험한 리디 APK SHA-256: `FFD5D438D3227E667D840DC197A319E1D702FC9CAEAE26E71D6C4499864E6019`
- 순정 `app_process` SHA-256: `28EF0CB30DFC2743050E64BAC6B35FF749149D908C82D05B019F2D7746B91848`
- [Xposed API 사용 안내](https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API): Android 4.x는 API 53을 쓰고, API 구현은 모듈에 넣지 않아요.
- [Android SSLSocket 문서](https://developer.android.com/reference/javax/net/ssl/SSLSocket): TLS 1.2는 API 16부터 있지만 기본으로 켜지는 것은 API 20부터라서 직접 켜요.
