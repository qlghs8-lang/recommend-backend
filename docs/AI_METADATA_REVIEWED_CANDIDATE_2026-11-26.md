# 3단계 — 실제 필드 판정과 비활성 후보 범위 확정

2026-11-26 KST. 고정 수집 배치의 **767관계 모두에 명시적 Codex 판정**을 작성했다. 로컬 검토 파일 기준 APPROVE101/HOLD666이며 컴파일 결과는 **비활성 후보101개**다. 실제 서비스 DB에 승인/저장/활성화하지 않았고 Content·검색·추천 동작은 변경하지 않았다. 3단계 전체는 계속 진행 중이다.

검토 기록: [metadata-review-decisions-v1.json](evaluation/metadata-review-decisions-v1.json). 기존 26개 라벨 제안을 자동 승인하는 도구를 추가한 것이 아니라, 실제 원문을 읽고 선택/보류 범위·라벨·중심성·근거를 이 기록에 명시했다. `scripts/metadata_review_release.py`와 이전 스키마/검사/필드 계획은 변경하지 않았다.

## 판정 범위와 식별자

| 대상 | 전체 | 후보 선택 | 보류 | 판정 범위 |
|---|---:|---:|---:|---|
| 키워드 | 413 | 26 | 387 | 고정 줄거리와 한국어 라벨을 대조한 후보 발견 단서. CENTRAL22/SUPPORTING4는 Codex 해석 |
| 제목 별칭 | 334 | 55 | 279 | 공식 한국어 제목28 + 원제27. 정규화된 작품/별칭 키는 중복을 제외하면49개 |
| 시간 후보 | 20 | 20 | 0 | 영화 전체11/TV 대표 회당9. 반환값/단위/고정 null 기준의 기술 검토이며 Content 반영 승인 아님 |
| 합계 | **767** | **101** | **666** | 누락0, 사람 판정0, 서비스 활성0 |

- collection SHA: `9cb6bff327f7c73403bbbf66ac65c81fa6fea9dcc724d580e827e49e22818954`
- 원문 projection SHA: `15d49dbc6f90487ceac359fa4a82adb2d05bf5eacf076593f7d10ca7efd9391c`
- 필드 검토 계획 SHA: `88811fc8632bae1fa15c336335db37a578908ec7be01e62eb5fceef33e9cf35a`
- 새 판정 문서 canonical SHA: `e0ea225a5516fb7c313245e272b2a0ea5530042bec53974d7fc497e7ce84168d`
- 새 판정 파일 SHA-256: `30e32d14cc77e7b4f164b63334cf9ccbbc68c089eb114a7781a3410630278456`
- 부모 미검토 버전: `e05c086a56a9f414271e764f584038c38cae531f43bf7fb77cad3644fd04b6c2`
- 새 검토 버전: `a98e2fafdf7c5bea09a9a90758815eceae01eab4cce789ff5766fca004ed8b21`
- 비활성 후보: `fa075eb4a7d34cf575eb7013d46bce3ad32cfaed3fcc5f8c3cd0c3d37a648bfb`

모든 기록은 CODEX / Codex field review 2026-11-26 / 2026-11-26T12:55:39Z 선언과 이유를 갖는다. HUMAN으로 가장하거나 사람 독립 검토로 집계하지 않는다. 시각은 이번 최종 판정 묶음의 선언 시각이며 관계별 작업 시간을 측정한 값이 아니다. 입력의 APPROVE는 제한된 로컬 판정이고, DB의 approved/active나 운영 승인을 의미하지 않는다. 검토자 신원은 DECLARED_NOT_AUTHENTICATED다.

## 선택한 키워드26개

| 작품 ID / 제목 | 원문 → 한국어 라벨 | Codex 중심성 |
|---|---|---|
| 45 니모를 찾아서 | fish → 물고기 | CENTRAL |
| 47 프리 솔로 | rock climbing → 암벽등반 | CENTRAL |
| 53 토이 스토리 | toy → 장난감 | CENTRAL |
| 59 곡성 | police → 경찰 | SUPPORTING |
| 76 신 고질라 | giant monster → 거대 괴수 | CENTRAL |
| 78 브루클린 나인-나인 | police → 경찰 | CENTRAL / SETTING |
| 117 마스크 | transformation → 변신 | CENTRAL |
| 136 사자 | exorcism → 구마 의식 | CENTRAL |
| 177 더 배트맨 | murder investigation → 살인 수사 | CENTRAL |
| 188 86 에이티식스 | war → 전쟁 | CENTRAL |
| 190 체인소 맨 레제편 | chainsaw → 전기톱 | SUPPORTING |
| 199 스파이더맨 | superhero → 슈퍼히어로 | CENTRAL |
| 205 머나먼 정글 | vietnam war → 베트남전쟁 | CENTRAL |
| 257 고릴라 이야기 | gorilla → 고릴라 | CENTRAL |
| 258 케빈은 열두살 | coming of age → 성장기 | CENTRAL |
| 268 온슬로트 | super soldier → 슈퍼 솔저 | CENTRAL |
| 309 킹 오브 더 힐 | family → 가족 | CENTRAL |
| 322 굿보이 | police → 경찰 | CENTRAL |
| 332 블루 플래닛 II | marine life → 해양 생물 | CENTRAL |
| 359 드라이브 - 계약 관계 | racing → 레이싱 | SUPPORTING |
| 405 하이퍼나이프 | brain surgeon → 신경외과의사 | SUPPORTING |
| 428 서른이지만 열일곱입니다 | waking from coma → 혼수상태에서 깨어남 | CENTRAL |
| 441 트윈 픽스 | murder mystery → 살인 미스터리 | CENTRAL |
| 456 비스타즈 | anthropomorphic animal → 의인화된 동물 | CENTRAL / SETTING |
| 466 브로드처치 | murder investigation → 살인 수사 | CENTRAL |
| 476 기묘한 이야기 | experiment → 실험 | CENTRAL |

모든 라벨 버전은 codex-ko-20261126-v1, 사용 범위는 **DISCOVERY_ONLY**다. 26개는 작품-키워드 관계 수이며 키워드 고유 ID 수와 구분한다. 줄거리에서 실제 발췌한 문장과 해석 이유를 각 기록에 넣었다. 인용 존재·CENTRAL 표기가 독립적인 의미 정답이나 모든 요청에 대한 적합성을 보장하지 않는다. 기존 원문 payload의 언어UNKNOWN/중심성UNJUDGED는 그대로 보존한다.

주요 조정:

- 프리 솔로47: 이전 `free climbing → 맨몸 암벽등반` 제안은 이번에 승격하지 않았다. 저장 원문에 있는 다른 관계 `rock climbing`(keyword201008)을 **암벽등반**으로 선택했다. 줄거리의 직접 표현에 범위를 맞추기 위한 판정이며 서로 다른 등반 용어를 무조건 동의어로 만들지 않았다. 원래 제안/키워드는 삭제하지 않았다.
- 드라이브359의 레이싱, 하이퍼나이프405의 신경외과의사, 곡성59의 경찰, 레제편190의 전기톱은 SUPPORTING으로 제한했다. 인물/배경 단서를 작품 전체의 유일한 중심 주제로 강화하지 않는다.
- 188의 근거는 ‘전장’이라는 짧은 도입만이 아니라 소년소녀가 실제로 전투에 투입되는 줄거리 문장을 포함하도록 최종 보완했다. 첫 초안은 target의 compiled/로그로 보존하고 최종본은 compiled-final로 구분했다.
- 헬로우 고스트156은 정상 빈 키워드를 유지하고, 뚱이쇼410은 이야기 정보가 부족해 키워드를 선택하지 않았다. 해당 작품의 별칭/시간 후보 검토와는 별개다.

## 보류666개와 별칭/시간 경계

키워드387개는 판정 사유를 붙여 보류했다. 일반 라벨/중심성 확정 범위 밖373, 감정·감상 용도9, 중심 주제 확대 위험4, 번역 범위1이다. 보류는 원문이 틀렸다는 판정이나 자동 삭제가 아니다.

coffee/diner를 요리 중심 소재로, toy car를 경주 중심 소재로, formula one (f1)을 줄거리가 입증한 세부 경기로 승격하지 않았다. comforting/romantic/healing/family 등의 존재만으로 감상 효과·로맨스 중심성·가족 관람 안전성을 보장하지 않는다. 실제 선택한 family309도 가족 서사를 뜻할 뿐 안전성 인증이 아니다. 이번 배치에서 요리 중심 후보를 확인하지 못했으며, 카탈로그 전체의 의미상 부재나 사용자 요청 실패 원인을 확정한 것은 아니다.

별칭은 한국어 현재 제목28개가 고정 공개 제목과 정확히 일치함을 확인했다. 원제는27개를 문자 표기와 동일 source ID 범위로 선택했으며 외부 공식성·모든 국가의 표준 표기를 독립 검증한 것은 아니다. 작품과 정규화 별칭 키가 같은6쌍은 관계/출처를 유지하되 미래 검색 사용 시 중복 효과를 제거해야 한다. 임의 ID 병합은 허용하지 않는다.

- 다른 제목278개는 모두 보류했다. 기존 시즌/판본/가제/오타 위험14개와 나머지 언어/전체 작품 범위 미확정264개다. 국가를 언어로 사용하지 않았다.
- 비스타즈456의 원제 BEASTARS는 작품 원어 ja와 라틴 문자 표기만으로 제목 문자열 언어를 확정하지 않아 추가 보류했다. 현재 한국어 제목은 별개 관계로 선택했다.
- 드라이브359의 원제 Drive – The Pretenders는 영어 표기로 판단했다. 작품 원어 de를 제목 문자열의 언어로 자동 복사하지 않았다. 이는 Codex의 문자 해석이며 독립 언어 감수 결과가 아니다.
- 기존 정규화 제목 충돌4쌍은 보존했다. 선택된55관계는 현재 고정 카탈로그/별칭에서 다른 작품으로 모호하게 연결되지 않는 범위만 포함한다.

시간20개는 기존 [시간 후보 목록](AI_METADATA_FIELD_REVIEW_2026-11-26.md)의 값과 동일하다. 영화11개는 MOVIE_TOTAL, TV9개는 REPRESENTATIVE_EPISODE이며 반환값/단위/기준 null 검토만 수행했다. 외부 상영시간 사실 대조, 모든 회차 정확성, 현재 DB null, 실제 Content 반영은 승인하지 않았다. 기존 동일 시간3개와 정상 빈 TV5개를 새 후보로 만들지 않았다. 기존 시간21개/미등록447개는 이 작업으로 바꾸지 않는다.

용어 판단을 보조하려고 공식 [UIAA 암벽등반 자료](https://www.theuiaa.org/grades-standards/rock-climbing/)를 열람했다. BMC 문서는 검색 결과를 확인했지만 본문은403으로 열리지 않았다. 이 참고 열람을 영화 사실/한국어 전문 용어의 독립 검증 완료로 집계하지 않는다. 새 TMDB 데이터 API·LLM 호출은0이다.

## 실행한 검증과 격리 재생

실제 최종 기록을 기존 CLI로 컴파일했다:

```powershell
python -X utf8 scripts/metadata_review_release.py plan --collection target/metadata-live-store-20261126/recovery-plan/input/collection.json --decisions docs/evaluation/metadata-review-decisions-v1.json --output target/metadata-reviewed-candidate-20261126/compiled-final
```

작업본/Eclipse 각각 **24항목 산출물 검증 PASS**: 767관계 1:1/중복·누락0, 원본 값 해시, CODEX 선언, 승인/보류와 선택의 일치, 종류별 수,28작품 ID, 정확한 인용,22/4 중심성, 등반 관계 조정,188 근거, 주제/분위기 확대 금지, 별칭 언어/범위와49개 키, 시간 단위/기준 null, 부모/기존 미검토 기록, 비활성/Content updates0, 기존 컴파일러 재생 및 양쪽 self-hash 등을 확인했다. 이는 코드 단위검사24개나 의미 정확도24/24라는 뜻이 아니다.

작업본/Eclipse 각각 새 UUID 소유 임시 MySQL 스키마에서 **실제 최종 자료 저장 시나리오1회 PASS**:

1. 고정 공개 원문1,048행을 임시 스키마에만 재생.
2. 기존 미검토 부모 버전1+기록767 저장:768행.
3. 새 판정 버전1+기록767+비활성 후보1 저장:769행.
4. 합계 버전2/기록1,534/후보1, 선택101/active0.
5. 동일 판정 재실행 추가0, 부모 버전/타임스탬프·원문/카탈로그/매핑·기존 시간21개·조회수/기존 시간출처 보존.
6. 정확한 소유 임시 스키마 삭제와 부재 확인.

기존 제품/도구 코드를 바꾸지 않아 이전 기능의 오프라인40/격리MySQL10/Java/React/실제 모델 회귀를 반복하지 않았다. 이전 결과를 이번 새 검사 수에 더하지 않는다. 이번 검증은 실제 새 판정 자료를 사용했다. 서비스 DB 테이블 조회/DDL/DML은0이고 현재DB 상태는 NOT_CHECKED다.

양쪽 target/metadata-reviewed-candidate-20261126에 compiled-final 5산출물, candidate-validation.json, scratch-verification.json이 있다. 작업본에는 build_decisions.py/validate_candidate.py/rehearse_scratch.py, 초안/최종 보완 로그, 초기 파일 manifest와 최종 verification.json도 보존했다. 재생 코드는 target의 이번 자료용이며 일반 자동 승인 규칙이 아니다.

## 동기화와 다음 단일 작업

검토 기록/보고서2개 + 진행/로드맵/고도화계획3개, 총5파일을 Eclipse 실행본에 선택 동기화했다. 기존3문서는 작업본 target/metadata-reviewed-candidate-20261126/before 및 실행본 target/metadata-reviewed-candidate-sync-20261126/before에 먼저 백업했다. 추가 지침/초기 차이를 확인했으며 설정·자격증명·무관 소스를 복사하지 않았다. 최종 해시·기존 파일/프런트 보존·양쪽 5산출물/검증 결과 일치는 verification.json에 기록했다.

**Eclipse F5만 필요하며 백엔드/IDE 재시작은 필요 없다.** 실행 중 앱을 재시작하지 않았다. 실제 챗봇의 검색/추천 응답은 그대로다.

**다음 단일 작업:** 이 고정 판정/101개 후보를 서비스 DB에 **비활성 검토 릴리스로 저장하는 통제된 경로 구현·검증·적용**. 코드/입력/대상/기준 상태 고정, 기존 부모 이력, 좁은 백업·잠금·원자성·영수증/명시적 재실행을 포함하고 원문/Content를 보존한다. 실제 검색 활성화·Content 시간 반영·추천 정책 변경·작품 추가는 별도이며 다음 요청 전에는 시작하지 않는다.

운영용 검토자 신원 인증·독립 의미 검토·활성화 정책/회수/부하·검색 품질 비교는 미완료다. 기존 품질 평가의 사람0/2·의미UNJUDGED·별도검증24턴 미실행·과거 오류3/36·사전 독립풀/반복/실환경과 승인총$3/누적예약$1.1838605/실청구UNKNOWN을 유지한다. 이번 Codex의26관계 중심성 판정을 기존 평가의 독립 정답이나 추천 품질 개선 측정으로 바꾸지 않는다.
