# 위키 그림 편집하기

[위키 시작](../README.md)

위키의 그림은 Excalidraw로 만든다. 이 디렉토리의 `.excalidraw`가 편집 원본이고 같은 이름의 `.svg`가 Markdown에 표시되는 이미지다. `.svg`만 수정하면 원본과 달라지므로 먼저 원본을 바꾼다. 도식 목록과 현재 구현·후속 설계 표시는 [index.json](index.json)에 있다.

## 브라우저에서 편집

1. [Excalidraw](https://excalidraw.com)를 열고 편집할 `.excalidraw` 파일을 끌어 놓는다. 또는 메뉴의 Open으로 연다.
2. 도형·텍스트·화살표를 편집한다. 그림에 있는 현재 구현·미구현 라벨도 함께 맞춘다.
3. Save to file로 같은 이름의 `.excalidraw`를 저장한다.
4. Export image에서 SVG와 흰 배경을 선택해 같은 이름의 `.svg`를 저장한다. 아래 렌더링 명령으로 SVG를 만들 수도 있다.
5. 원본·SVG·설명을 함께 검토하고 커밋한다. 이미지에만 새 정책을 추가하지 않는다.

화살표 라벨과 본문을 함께 읽을 수 있도록 그림을 구성한다. 색만으로 결과를 구분하지 않고 성공·실패·미구현을 글자로도 쓴다. 한 그림이 너무 길어지면 설명 단위로 나눈다.

## 원본에서 SVG 다시 만들기

Java 서버 빌드와 별개인 문서 도구다. Node.js 22.12 이상과 로컬 Chrome이 필요하며, 도식 데이터를 외부 서비스로 업로드하지 않는다. 의존성 버전은 `tools/wiki-diagrams/package-lock.json`으로 고정한다.

저장소 루트에서 실행한다.

```sh
npm ci --prefix tools/wiki-diagrams
npm run render --prefix tools/wiki-diagrams
python3 scripts/check-wiki-diagrams.py
```

기본 Chrome 경로는 macOS의 `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`이다. 다른 환경에서는 실행할 Chrome 또는 Chromium 경로를 `CHROME_PATH` 환경변수로 지정한다. 렌더러는 127.0.0.1의 임시 서버와 headless Chrome을 사용하고 작업 후 종료한다. 한글 표시를 위해 실행 환경에 한글 글꼴도 필요하다.

전체 원본을 렌더링하므로 사용하지 않는 변경이 섞이지 않았는지 diff를 확인한다. 그림 내용은 `.excalidraw`에서 유지하며 과거 Mermaid 코드를 다시 생성 원본으로 사용하지 않는다.

## 그림별 역할

- 파랑 계열 상자: 요청 처리·일반 데이터 흐름
- 노랑 마름모·구간: 조건 또는 분기
- 초록·빨강 계열: 일부 성공·거절·반환 지점 강조. 정확한 결과는 상자와 화살표 라벨을 따른다.
- 보라 계열: 참고·예정 흐름 강조. 모든 보라 상자가 미구현이라는 뜻은 아니며 그림 상단과 해당 문단의 구현 상태를 따른다.

그림은 문서의 설명을 돕는 자료다. 사용자 식별·수량·금액·PG 결과 처리의 원문은 각 단계 정책과 실제 코드에서 확인한다.
