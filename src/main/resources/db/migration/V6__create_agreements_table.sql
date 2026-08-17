CREATE TABLE agreements (
    id BIGSERIAL PRIMARY KEY,
    type VARCHAR(50) NOT NULL UNIQUE,
    title VARCHAR(255) NOT NULL
);

CREATE TABLE agreement_articles (
    id BIGSERIAL PRIMARY KEY,
    agreement_id BIGINT NOT NULL REFERENCES agreements(id) ON DELETE CASCADE,
    title VARCHAR(255) NOT NULL,
    body TEXT NOT NULL,
    sort_order INT NOT NULL
);

-- Terms Initial Data
INSERT INTO agreements (type, title) VALUES ('terms', '이용약관');

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제1조 (목적)', '이 약관은 (주)포스셀렉트(이하 "회사")가 운영하는 PosSelect(이하 "쇼핑몰")에서 제공하는 인터넷 관련 서비스(이하 "서비스")를 이용함에 있어 회사와 이용자의 권리·의무 및 책임사항, 절차 등 기본적인 사항을 규정함을 목적으로 합니다.', 1
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제2조 (정의)', '① "쇼핑몰"이란 회사가 재화 또는 용역을 이용자에게 제공하기 위하여 컴퓨터 등 정보통신설비를 이용하여 재화 또는 용역을 거래할 수 있도록 설정한 가상의 영업장을 말합니다.
② "이용자"란 쇼핑몰에 접속하여 이 약관에 따라 회사가 제공하는 서비스를 받는 회원 및 비회원을 말합니다.
③ "회원"이란 쇼핑몰에 개인정보를 제공하여 회원등록을 한 자로서, 쇼핑몰의 정보를 지속적으로 제공받으며 서비스를 계속적으로 이용할 수 있는 자를 말합니다.', 2
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제3조 (약관의 게시와 개정)', '① 회사는 이 약관의 내용을 이용자가 쉽게 알 수 있도록 쇼핑몰 초기 화면 또는 연결화면을 통해 게시합니다.
② 회사는 「전자상거래 등에서의 소비자보호에 관한 법률」, 「약관의 규제에 관한 법률」 등 관련 법령을 위배하지 않는 범위에서 이 약관을 개정할 수 있습니다.
③ 회사가 약관을 개정할 경우에는 적용일자 및 개정사유를 명시하여 현행 약관과 함께 적용일자 7일 이전부터 적용일자 전일까지 공지합니다. 다만 이용자에게 불리한 내용으로 변경하는 경우에는 최소 30일 이상의 유예기간을 두고 공지합니다.', 3
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제4조 (회원가입)', '① 이용자는 회사가 정한 가입 양식에 따라 회원정보를 기입한 후 이 약관에 동의한다는 의사표시를 함으로써 회원가입을 신청합니다.
② 회사는 제1항과 같이 회원으로 가입할 것을 신청한 이용자 중 다음 각 호에 해당하지 않는 한 회원으로 등록합니다.
  1. 가입신청자가 이 약관에 의하여 이전에 회원자격을 상실한 적이 있는 경우
  2. 등록 내용에 허위, 기재누락, 오기가 있는 경우
  3. 기타 회원으로 등록하는 것이 회사의 기술상 현저히 지장이 있다고 판단되는 경우', 4
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제5조 (서비스의 제공 및 변경)', '① 회사는 다음과 같은 업무를 수행합니다.
  1. 재화 또는 용역에 대한 정보 제공 및 구매계약의 체결
  2. 구매계약이 체결된 재화 또는 용역의 배송
  3. 기타 회사가 정하는 업무
② 회사는 재화 또는 용역의 품절 또는 기술적 사양의 변경 등의 경우에는 장차 체결되는 계약에 의해 제공할 재화 또는 용역의 내용을 변경할 수 있습니다.', 5
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제6조 (서비스 이용시간)', '서비스 이용은 회사의 업무상 또는 기술상 특별한 지장이 없는 한 연중무휴, 1일 24시간을 원칙으로 합니다. 다만 시스템 정기점검, 증설 및 교체를 위해 회사가 정한 날 또는 시간에는 서비스가 일시 중지될 수 정되며, 이 경우 회사는 사전에 공지합니다.', 6
FROM agreements WHERE type = 'terms';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '제7조 (구매신청 및 계약의 성립)', '① 이용자는 쇼핑몰상에서 다음 또는 이와 유사한 방법에 의하여 구매를 신청하며, 회사는 이용자가 구매신청을 함에 있어 다음의 각 내용을 알기 쉽게 제공하여야 합니다.
  1. 재화 등의 검색 및 선택
  2. 받는 사람의 성명, 주소, 전화번호 등 입력
  3. 결제방법의 선택
  4. 이 약관에 대한 동의 및 제3항의 각 내용에 대한 확인
  5. 재화 등의 구매신청 및 이에 관한 확인 또는 회사의 확인에 대한 동의
② 회사는 이용자의 구매신청에 대하여 승낙의 의사표시를 하는 것을 원칙으로 하며, 회사가 승낙의 통지를 하는 시점에 계약이 성립한 것으로 봅니다.', 7
FROM agreements WHERE type = 'terms';

-- Privacy Initial Data
INSERT INTO agreements (type, title) VALUES ('privacy', '개인정보 수집 및 이용 동의');

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '1. 개인정보의 수집 항목 및 수집 방법', '회사는 회원가입, 상담, 서비스 신청 등을 위해 아래와 같은 개인정보를 수집합니다.
  · 회원가입 시(필수): 이메일, 비밀번호, 이름
  · 주문/배송 시(필수): 수령인 이름, 배송지 주소, 연락처
  · 결제 시(필수): 결제수단 정보(카드사, 계좌 등 — 카드번호 등 민감정보는 PG사가 처리하며 회사는 저장하지 않습니다)
  · 서비스 이용 과정에서 자동 생성: IP 주소, 쿠키, 접속 로그, 서비스 이용기록
수집 방법: 홈페이지 회원가입 및 주문, 고객센터 상담, 이벤트 응모 과정에서 이용자가 직접 입력.', 1
FROM agreements WHERE type = 'privacy';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '2. 개인정보의 수집 및 이용 목적', '회사는 수집한 개인정보를 다음의 목적을 위해 이용합니다.
  · 회원 식별 및 본인여부 확인, 부정이용 방지
  · 재화 또는 용역의 공급에 따른 계약이행 및 요금 정산(콘텐츠 제공, 구매·요금 결제, 물품배송)
  · 신규 서비스 개발 및 마케팅·광고에의 활용(이벤트 정보 및 참여기회 제공, 광고성 정보 제공 — 별도 동의한 경우에 한함)
  · 민원사무 처리(민원인의 신원 확인, 민원사항 확인, 처리결과 통보)', 2
FROM agreements WHERE type = 'privacy';

INSERT INTO agreement_articles (agreement_id, title, body, sort_order)
SELECT id, '3. 개인정보의 보유 및 이용기간', '회사는 원칙적으로 개인정보 수집 및 이용목적이 달성된 후에는 해당 정보를 지체 없이 파기합니다. 다만 관계 법령의 규정에 의하여 보존할 필요가 있는 경우 회사는 아래와 같이 관계 법령에서 정한 일정한 기간 동안 회원정보 보관합니다.
  · 계약 또는 청약철회 등에 관한 기록: 5년 (전자상거래 등에서의 소비자보호에 관한 법률)
  · 대금결제 및 재화 등의 공급에 관한 기록: 5년 (전자상거래 등에서의 소비자보호에 관한 법률)
  · 소비자의 불만 또는 분쟁처리에 관한 기록: 3년 (전자상거래 등에서의 소비자보호에 관한 법률)
  · 로그인 기록: 3개월 (통신비밀보호법)', 3
FROM agreements WHERE type = 'privacy';
