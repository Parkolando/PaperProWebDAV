# 함께 배포하는 다른 프로젝트의 파일

원래 배포처가 사라져도 설치와 빌드를 계속할 수 있도록, 다른 프로젝트의 파일 가운데 **라이선스가 재배포를 허용하는 것**을 이 저장소와 릴리스에 함께 올려 두었어요. 각 파일은 원본 그대로이고 고치지 않았어요.

## 릴리스에 올린 파일

| 파일 | 프로젝트 | 라이선스 | SHA-256 |
| --- | --- | --- | --- |
| `XposedInstaller_2.7_experimental1.apk` | [Xposed Installer](https://github.com/rovo89/XposedInstaller/tree/2.7-experimental1) 2.7 experimental1 (rovo89, Tungstwenty) | Apache License 2.0 | `6a97741c1cf710c6cc0cbede4856bc1ad191f13ae9761c518c3fe25c2c22a48c` |

Xposed Installer APK 안에는 Xposed 프레임워크(`app_process`, `XposedBridge.jar`)와 BusyBox가 들어 있어요. 원본의 고지문은 [`licenses/XposedInstaller-NOTICE.txt`](licenses/XposedInstaller-NOTICE.txt)에 그대로 옮겨 두었어요. 소스는 [rovo89/XposedInstaller](https://github.com/rovo89/XposedInstaller), [rovo89/XposedBridge](https://github.com/rovo89/XposedBridge), [rovo89/Xposed](https://github.com/rovo89/Xposed)에 있어요.

## 저장소의 `lib/` 폴더 (빌드 · 시험용)

모듈 APK에는 들어가지 않고, 컴파일하거나 PC에서 시험할 때만 써요.

| 파일 | 프로젝트 | 쓰는 곳 | 라이선스 | SHA-256 |
| --- | --- | --- | --- | --- |
| `xposed-api-53.jar` | [XposedBridge](https://github.com/rovo89/XposedBridge) API 53 | 컴파일 | Apache License 2.0 | `5b15d81fad60ecea65d44dd6e9fc9374c6d5eb4782da669212050d37d1d66eef` |
| `okhttp-3.12.13.jar` | [OkHttp](https://github.com/square/okhttp) 3.12.13 (Square) | 컴파일, PC 시험 | Apache License 2.0 | `508234e024ef7e270ab1a6d5b356f5b98e786511239ca986d684fd1e2cf7bc82` |
| `okio-1.15.0.jar` | [Okio](https://github.com/square/okio) 1.15.0 (Square) | 컴파일, PC 시험 | Apache License 2.0 | `693fa319a7e8843300602b204023b7674f106ebcb577f2dd5807212b66118bd2` |
| `kxml2-2.3.0.jar` | [kXML 2](https://github.com/kobjects/kxml2) 2.3.0 (Stefan Haustein), XmlPull API 포함 | PC 시험 | MIT 방식(kXML), 퍼블릭 도메인(XmlPull API) | `f264dd9f79a1fde10ce5ecc53221eff24be4c9331c830b7d52f2f08a7b633de2` |

기기에서는 리디 앱에 이미 들어 있는 OkHttp · Okio와 안드로이드의 XML 파서를 쓰기 때문에, 이 파일들은 기기로 가지 않아요.

## 라이선스 전문

- [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt): Apache License 2.0
- [`licenses/XposedInstaller-NOTICE.txt`](licenses/XposedInstaller-NOTICE.txt): Xposed Installer의 고지문
- [`licenses/XposedBridge-NOTICE.txt`](licenses/XposedBridge-NOTICE.txt): XposedBridge의 고지문
- [`licenses/kxml2-license.txt`](licenses/kxml2-license.txt): kXML 2의 라이선스

## 함께 올리지 않은 것

| 대상 | 이유 | 받는 곳 |
| --- | --- | --- |
| [PaperProKitchen](https://github.com/limerainne/PaperProKitchen) (루트 이미지, 도구 묶음) | 저장소에 라이선스가 없어서 재배포 허락을 받은 것으로 볼 수 없어요. 이미지 안에 SuperSU 같은 다른 사람의 프로그램도 들어 있어요. | [r13_2 릴리스](https://github.com/limerainne/PaperProKitchen/releases/tag/r13_2) |
| Android SDK Platform-Tools (ADB · fastboot), Google USB 드라이버 | Google의 SDK 약관이 재배포를 허용하지 않아요. | [Platform-Tools](https://developer.android.com/tools/releases/platform-tools), [USB 드라이버](https://developer.android.com/studio/run/win-usb) |
| 리디 앱 APK와 디컴파일 결과 | 리디의 저작물이에요. `stubs/`에는 컴파일에 필요한 이름 선언만 있어요. | 직접 기기에서 꺼내요 |

## 순정 펌웨어에 대해

`firmware-1.6.5P` 릴리스의 파티션 이미지는 리디 페이퍼 프로의 순정 소프트웨어이고, 저작권은 리디와 각 권리자에게 있어요. 오픈소스 라이선스로 받은 것이 아니라, 기기가 켜지지 않을 때 자기 기기를 되살리는 용도로만 올려 둔 파일이에요.
