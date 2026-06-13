# 📦 CHAIN-G (Back-end)

<img width="300" alt="Gemini_Generated_Image_8j8ddp8j8ddp8j8d-Photoroom" src="https://github.com/user-attachments/assets/6f623ede-7a0d-4fcd-a74f-3bec56de2c63" />

> CONNECT GOOD, VALUE CHAIN
>
**CHAIN-G**는 떡볶이 밀키트의 생산부터 유통, 가맹점 판매 및 정산까지의 전 과정을 효율적으로 관리하는 **공급망 관리(SCM) 시스템**입니다.  
본사, 가맹점, 공장 세 주체 간의 유기적인 발주, 재고 관리, 물류 배차 및 정산 프로세스를 자동화합니다.

<br>

## 👥 팀원 소개 (Team Flow-er)

| <img src="https://github.com/chaewoo-kim.png" width="120"> | <img src="https://github.com/rlatjddms.png" width="120"> | <img src="https://github.com/kyk5095.png" width="120"> | <img src="https://github.com/Yoocy0.png" width="120"> | <img src="https://github.com/cho-yunho01.png" width="120"> |
| :---: | :---: | :---: | :---: | :---: |
| **김채우** | **김성은** | **김윤경** | **유찬연** | **조윤호** |
| Order / Sales / <br>Return | User / BusinessUnit / <br>Notice / Notification | Settlement | In&Outbound / <br>Transport | Inventory / Product |
| [@chaewoo-kim](https://github.com/chaewoo-kim) | [@rlatjddms](https://github.com/rlatjddms) | [@kyk5095](https://github.com/kyk5095) | [@Yoocy0](https://github.com/Yoocy0) | [@cho-yunho01](https://github.com/cho-yunho01) |

<br>

## 🛠 기술 스택

| Category | Stack |
| :--- | :--- |
| **Language** | Java 21 |
| **Framework** | Spring Boot 3.2.2 |
| **Persistence** | Spring Data JPA, MySQL 8.x |
| **Build Tool** | Gradle |
| **Testing** | JUnit 5, AssertJ, JaCoCo (Line Coverage 80% 이상 준수) |
| **CI/CD & Tools** | SonarQube, Lombok, GitHub Actions |

<br>

## 🏢 프로젝트 아키텍처

본 프로젝트는 유지보수성과 확장성을 위해 **멀티 모듈 아키텍처**를 채택하고 있으며, 도메인 중심 설계(Domain-Driven Design)를 지향합니다.

### 모듈 구조
- **`module-core`**: 시스템 전반에서 공통으로 사용되는 유틸리티, 예외 처리, 공통 응답 규격을 포함합니다.
- **`module-domain`**: 순수 비즈니스 로직과 데이터 모델(Entity)을 담당하는 핵심 모듈입니다.
  - `domain-orders`: 가맹점 및 본사의 발주(Order) 프로세스
  - `domain-inventories`: 공장 및 가맹점의 재고(Inventory) 관리
  - `domain-settlements`: 가맹점 수익 및 본사 정산(Settlement) 로직
  - `domain-transports`: 물류 및 차량 배차(Logistics) 정보
  - `domain-users`: 회원 정보 및 권한 관리
  - *기타 도메인*: `products`, `notices`, `notifications`, `sales`, `returns` 등
- **`module-app:app-api`**: 외부 요청을 처리하는 API 엔드포인트를 제공하며 도메인 모듈을 조합하여 실제 서비스를 제공합니다.
- **`module-external-transport`**: 외부 시스템(물류 시스템 등)과의 연동을 담당합니다.

<br>

## 🚀 주요 비즈니스 프로세스

### 1. 발주 및 생산 (Ordering & Production)
- **가맹점 -> 본사 발주**: 가맹점에서 부족한 밀키트 품목을 본사에 요청합니다. (화/금 도착 기준)
- **본사 -> 공장 생산 요청**: 본사에서 전체 가맹점의 수요를 취합하여 공장에 생산 지시를 내립니다.
- **제품 코드 체계**: 제품명 + 매운맛(01~04) + 사이즈(01, 03)의 조합으로 관리됩니다.

- ### 2. 물류 및 재고 (Logistics & Inventory)
- **박스 및 제품 식별 코드**: 생산된 제품은 지역/공장/생산라인 정보가 포함된 고유 코드로 관리됩니다.
- **피킹 및 차량 배차**: 출고 전 패키징 확정(피킹) 후 적재 중량을 고려하여 물류 차량에 자동/반자동 배차를 진행합니다.

### 3. 정산 (Settlement)
- **매출 및 대금 정산**: 가맹점의 판매 데이터를 기반으로 본사 대금 차감 및 수수료 정산을 수행합니다.
- **반품 관리**: 하자 상품에 대한 반품 요청 및 검수 후 대금 차감을 지원합니다.

<br>

## 📝 프로젝트 문서

### ⚡ 요구사항 정의서

<details>
<summary style="font-size:1.2em;">요구사항 정의서</summary>
<div markdown="1">

<img width="700" height="745" alt="image" src="https://github.com/user-attachments/assets/203abd45-0447-49d1-b954-cfd0bd375f1a" />
<img width="700" height="731" alt="image" src="https://github.com/user-attachments/assets/28d5387c-a88d-4534-a012-511a93a4a529" />
<img width="700" height="761" alt="image" src="https://github.com/user-attachments/assets/20fbda18-127a-4707-9fbc-1903d451266a" />
<img width="700" height="765" alt="image" src="https://github.com/user-attachments/assets/d664d485-f305-490a-bd8e-3fbdef7c76e9" />
<img width="700" height="431" alt="image" src="https://github.com/user-attachments/assets/70928ac3-7b13-434b-8bcd-c66487fedbcf" />

</div>
</details>


### ⚡ ERD

<details>
<summary style="font-size:1.2em;">ERD</summary>
<div markdown="1">

<img width="2000" alt="image" src="https://github.com/user-attachments/assets/63e01507-7fc2-416d-8d48-6569474a7b49" />

</div>
</details>

### ⚡ WBS

<details>
<summary style="font-size:1.2em;">WBS</summary>
<div markdown="1">

<img width="1008" height="826" alt="image" src="https://github.com/user-attachments/assets/02541551-296e-43ac-b28c-041cea26cd57" />

</div>
</details>

### ⚡API 테스트케이스

<details>
<summary style="font-size:1.2em;">API 테스트케이스</summary>
<div markdown="1">

<img width="700" alt="image" src="https://github.com/user-attachments/assets/38c1c675-0613-4c76-a4af-508448df9866" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/e58f34e8-800a-4c26-96c5-10e41541a1b6" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/226aa8d9-26ca-4ade-a037-1c65762ad495" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/49166126-3a81-4025-957f-e1e3a5d9ed60" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/5b5d2350-8a56-4d7b-a93a-490f7780fa43" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/00ff65a4-bbe8-41f9-853a-1ac4242feff5" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/4b717a16-3b25-4ccb-a20f-a493aa60dc4e" />
<img width="700" alt="image" src="https://github.com/user-attachments/assets/b0e30e1b-999a-47d5-aedf-0b797d694d6d" />

</div>
</details>

### ⚡ API 문서

<details>
<summary style="font-size:1.2em;">API 문서</summary>
<div markdown="1">

<img width="500" alt="image" src="https://github.com/user-attachments/assets/d6e11ee1-dcb7-47b1-b472-e898ca0f5bbc" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/6927f96e-2cad-412e-9378-1e822c15a31c" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/c5d4a1d4-95d7-4329-8337-34734aace578" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/f595012f-26ae-4eae-bbed-e02a87c0441a" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/f42a26cb-eed4-4615-b35c-3e4625be703a" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/aab13dd8-e671-4b41-820a-e4bb859bd1f6" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/2c1a326e-4a3d-45e7-bc62-9cd841e0d313" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/8cf6b0d9-0103-472e-a360-cb28bebff6bb" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/41b5b1af-af86-4949-a067-ce083c47bfe4" />
<img width="500" alt="image" src="https://github.com/user-attachments/assets/59ff62e9-4588-409b-8850-9b2a36f5776d" />

</div>
</details>

<br>

## 📖 개발 가이드 및 컨벤션

### 👥 팀 협업 규칙

- **데일리 스크럼**: 평일 오전 09:00 (10분 내외)
- **지라(Jira) 연동**: 작업 시작 전 이슈 생성 및 브랜치(`feat/이슈번호-도메인`) 생성 필수
- **품질 관리**: 모든 PR은 SonarQube 분석 및 테스트 통과를 기본으로 합니다.

### ⭐ Code Convention

<details>
<summary style="font-size:1.2em;">1. Naming (명명 규칙)</summary>
<div markdown="1">

- **패키지**: 언더스코어(`_`) 없이 소문자만 사용
- **클래스**: 대문자 카멜 케이스 (`UpperCamelCase`)
- **메서드**: 동사/전치사 시작, 소문자 카멜 케이스 (`lowerCamelCase`)
- **변수**: 소문자 카멜 케이스 (`lowerCamelCase`)
- **ENUM/상수**: 대문자 및 언더스코어 (`UPPER_SNAKE_CASE`)
- **DB 테이블**: 소문자 및 언더스코어 (`lower_snake_case`)
- **컬렉션**: 복수형 사용 혹은 명시 (`users`, `userList`, `userMap`)

</div>
</details>

<details>
<summary style="font-size:1.2em;">2. Comment & Import (주석 및 임포트)</summary>
<div markdown="1">

- **주석**: 한 줄 주석은 `//`, 여러 줄은 `/* ... */` 사용
- **파일 구조**: 소스파일당 1개의 탑레벨 클래스만 포함
- **Import**: 와일드카드(`*`) 사용 금지 (단, static import는 허용)
- **Annotation**: 선언 후 새 줄 사용 (파라미터 없는 1개는 같은 줄 허용)
- **기타**: 배열 대괄호는 타입 뒤에 (`String[]`), `long`형 값 끝에는 대문자 `L` 사용

</div>
</details>

<details>
<summary style="font-size:1.2em;">3. URL (RESTful API)</summary>
<div markdown="1">

- **행위 배제**: URL에 get, put 등 행위 표현 금지 (HTTP Method로 구분)
- **구분자**: `_` 대신 `-`(Kebab-case) 사용
- **형식**: 소문자 사용, 마지막 `/` 및 확장자 포함 금지

</div>
</details>

### ⭐ Commit Convention

<details>
<summary style="font-size:1.2em;">1. Git Flow & Rules</summary>
<div markdown="1">

- **프로세스**: Issue 생성 → Jira 티켓 관리 → feature 브랜치 생성 → add/commit/push → PR 생성 → dev 머지
- **기본 규칙**: `dev` 브랜치 직접 작업 금지 (README 제외), 모든 작업은 정상 실행 확인 후 수행
- **브랜치 네이밍**: `<Prefix>/<Ticket_Number>-<Domain>-<Description>`
  - 예시: `feat/7-order-create-order`, `feat/5-settlement-monthly`

</div>
</details>

<details>
<summary style="font-size:1.2em;">2. Commit Message & PR</summary>
<div markdown="1">

- **양식**: `[<Prefix>] #<Issue_Number> <Description>`
- **Prefix**:
  - `feat`: 새로운 기능 구현
  - `fix`: 버그 수정
  - `del`: 코드 삭제
  - `docs`: 문서 개정
  - `refactor`: 리팩터링
  - `chore`: 빌드 업무, 패키지 구조 변경, 의존성 추가
  - `test`: 테스트 코드 작성 및 수정

</div>
</details>

<br>

## 📝 프로젝트 회고

프로젝트를 마치며 팀원 각자가 느낀 기술적 도전과 성장을 기록합니다.

<details>
<summary style="font-size:1.2em;">🦫 김채우</summary>
<div markdown="1">

멀티 모듈을 도입해 배포까지 진행했던 프로젝트는 이번이 처음이었습니다.  
5명이서 협업을 하며 하나의 결과물을 냈습니다. 실제 도메인을 구매해 접속해 보는 과정은 새로웠습니다.  
2개월간 밀도 높은 시간으로 하나의 프로젝트에 몰입한 것 자체가 즐거웠습니다. 아쉬움도 많이 남지만 이 경험을 발판으로 앞으로 더욱 뛰어난 개발자가 되도록 노력하겠습니다. 

</div>
</details>

<details>
<summary style="font-size:1.2em;">☠️ 김성은</summary>
<div markdown="1">

이번 프로젝트에서는 회원, 사업장 관리, 공지사항 및 알림 기능을 담당했으며, 동시에 인프라를 맡아 CI/CD 환경을 구축했습니다.  
AWS를 처음 다뤄보는 상황이라 초반에는 어려움이 있었지만, 시행착오를 거치며 점차 익숙해졌고 실제 서비스 흐름을 고려한 배포 과정을 경험할 수 있었습니다.
또한 GitHub PR과 Jira 연동을 활용해 협업 프로세스를 체계적으로 경험했고, 팀원들과의 커뮤니케이션을 통해 기능을 조율하며 협업의 중요성을 느낄 수 있었습니다.  
이번 프로젝트는 새로운 기술을 직접 적용해보고 문제를 해결해 나가는 과정에서 많은 것을 배우고, 한 단계 성장할 수 있었던 의미 있는 경험이었습니다.

</div>
</details>

<details>
<summary style="font-size:1.2em;">🐇 김윤경</summary>
<div markdown="1">

단순한 기능 개발을 넘어 기획부터 설계, 개발까지 전 과정을 경험하면서 서비스가 만들어지는 흐름을 깊게 이해할 수 있는 시간이었다.  
각 도메인 간 상품의 이동과 돈의 흐름을 연결하여 하나의 시스템으로 바라볼 수 있었던 점이 가장 의미 있었다. 우리가 만든 ERP 시스템을 통해 비즈니스의 전반적인 흐름을 한눈에 파악할 수 있었던 것은 매우 값진 경험이었다.  
멘토님의 리뷰를 통해 부족한 점을 객관적으로 돌아볼 수 있었고, 데일리 스크럼을 통해 팀원들의 진행 상황을 공유하며 협업의 중요성도 체감할 수 있었다.  
많은 공을 들인 만큼 아쉬운 부분도 있었다. 초기 기획 단계에서 요구사항을 더 구체적으로 정의하지 못했던 점과, 서로 맡은 도메인 개발에 집중하다보니 소통이 부족했던 순간들이 아쉬웠다.  
이 경험을 통해 꾸준한 커뮤니케이션이 얼마나 중요한지 깨달았고, 앞으로는 이를 적극적으로 반영하여 개발을 하고싶다.

</div>
</details>

<details>
<summary style="font-size:1.2em;">🦘 유찬연</summary>
<div markdown="1">

지난 시간들동안 배운 것들을 토대로 파이널 프로젝트를 진행했다. 많은 팀 프로젝트에서 매번 중요성을 느끼게 된 것도 있었고, 반복됨에 따라 중요성을 새롭게 느끼게 된 것도 있었다.  
매번 중요성을 느끼게 된 것은 역시 소통이었다. 소통을 통해 우리가 하고자 하는 것이 같은 것인지를 확인하는 것이 중요했고, 구현이 되는 것에서도 통일되어야 했다.  
지난 모든 프로젝트들에서 이 소통이라는 것이 매번 조금씩은 아쉬움이 느껴졌었다. 다음 프로젝트에서는 더 잘할 수 있겠다고 생각했지만, 또 다른 부분에서 소통이 미흡함을 느껴 아쉬움을 느꼈다. 
프로젝트가 반복됨에 따라 중요도를 알게 된 것은 구조를 얼마나 견고하게 만드는 것인가 였다. 구조가 견고하지 못하면 서비스 로직에도 구멍이 많이 생기게 되고 작업을 수행할 때도 로직이 더 길어지는 등의 불편함이 있었다. 
그래도 미흡한 점만 있지는 않았다. 개발을 시작한 뒤로 이정도까지 몰두해서 개발을 진행한 경험이 처음이었고, 프로젝트를 진행함에 따라 개발을 어떤 식으로 진행해야 하는지 지금 당장 처한 상황에서 내가 할 수 있는 일이 무엇인지 등을 깨닫게 되었다. 
이러한 성장을 토대로 이후 진행하게 될 많은 프로젝트들에서 이런 미흡한 점들을 고려해 더욱 완성도 있는 팀 프로젝트를 만들고 싶다는 생각이 들었다. 그리 길지 않은 시간이었지만 많은 것들을 배웠고 느끼며 성장하는 값진 시간이었다.

</div>
</details>

<details>
<summary style="font-size:1.2em;">🐜 조윤호</summary>
<div markdown="1">

어느 정도 익혔다고 생각하고 파이널 프로젝트에 적용했지만, 예상보다 훨씬 어려웠다. 엔티티, 기능, 테스트를 반복적으로 수정하며 “더 효율적인 방법이 있었을 텐데”라는 아쉬움이 들었다.  
특히 초기 설계의 중요성을 크게 느꼈다. 기반이 탄탄하지 않으면 이후에 큰 수정이 반복된다는 것을 직접 경험했다.  
비록 쉽지 않은 과정이었지만, 다양한 시행착오를 통해 한 단계 성장할 수 있었던 의미 있는 경험이었다.

</div>
</details>



### 정산 고도화

스케줄러 배치
레디스 캐싱


### DevOps

1단계. GitHub Actions - 자동빌드
왜? - 현업이나 신규 프록트에서 많이 쓰기때문에, 어떻게 구현되는지 알고싶어서
목표: 깃허브에 코드를 푸시하면 자동으로 테스트를 돌리고 빌드하는 파일 하나를 만든다.
해볼 일: 프로젝트의 .github/workflows/ 폴더에 배포/빌드 설정 파일(.yml)을 만든다.
       깃허브에 올리고 초록색 불(Success)이 들어오는지 확인합니다.

2단계. GitHub Actions로 Docker 이미지 빌드 및 업로드
목표: 깃허브 액션을 통해 빌드된 결과물을 Docker 이미지로 만들어 Docker Hub나 AWS ECR에 올리는 단계까지 자동화해 봅니다.
학습 내용: 깃허브 보안 변수(Secrets) 관리, Docker Hub 로그인 연동 등.


3단계. Jenkins 서버 직접 구축 및 연동 
대규모 서비스나 레거시 시스템에 사용하기 때문에 확장성을 위해 고도화를 해본다.
리눅스 명령어, 포트 열기, 도커 권한 설정 등 서버 관리 지식이 많이 필요하기 때문입니다. 앞선 단계에서 자동화 흐름을 이해하고 도전하면 훨씬 덜 헷갈리기 때문에 git actions 후에 Jenkins 도입.

목표: 오라클 클라우드 프리티어나 AWS EC2를 하나 빌려서, 거기에 Jenkins를 직접 설치하고 깃허브 액션에서 했던 작업을 젠킨스용 스크립트(Jenkinsfile)로 옮겨 실행해 본다.

