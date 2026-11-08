/* 툴박스 설정
   api : 서버(toolbox-api) 주소
   site: 사이트 주소. 결과물 끝 "툴박스로 정리했어요" 한 줄에 들어가요.
   googleClientId: 구글 로그인 클라이언트 ID(…apps.googleusercontent.com). 넣으면 '선택 로그인'이 켜져요 — 로그인 안 해도 쓰고, 로그인하면 폰·PC 사이 기록이 이어져요
                   (서버에도 GOOGLE_CLIENT_ID 를 같은 값으로. 모두 로그인하게 하려면 서버 변수 LOGIN_REQUIRED = 1)
   beta: true 면 요금제 화면에 "베타 기간 무료"가 뜨고 결제 버튼 대신 "출시 알림 받기"가 떠요.
   contact: 문의 받을 이메일 (개인정보처리방침·이용약관에 표시). 예: "toolbox.help@gmail.com"
   aiPaid : Gemini 키를 결제 연결(유료 등급)했으면 true — 개인정보처리방침의 AI 안내 문구가 바뀌어요
   kakaoClientId: 카카오 로그인 REST API 키. 넣으면 '카카오로 계속하기' 버튼이 나와요 (서버 변수 KAKAO_CLIENT_ID 도 같은 값, 카카오 개발자 사이트에 Redirect URI = 사이트주소/ 등록)
   appleClientId: 애플 로그인 서비스 ID(예: com.toolbox.web). 넣으면 'Apple로 계속하기' 버튼이 나와요 (서버 변수 APPLE_CLIENT_ID 도 같은 값)
   pay : 프로 월간 결제 링크(페이앱 등) · payYear: 연간 결제 링크 (비우면 pay를 써요) */
window.TB_CONFIG = {api: "https://toolbox-api.khe121795.workers.dev", site: "https://muddy-pond-0d55.khe121795.workers.dev", googleClientId: "388811131620-e5c4kre78v73q7dl8mug6b9e4gce52ls.apps.googleusercontent.com", appleClientId: "", kakaoClientId: "", beta: true, pay: "", payYear: "", contact: "", aiPaid: false};
