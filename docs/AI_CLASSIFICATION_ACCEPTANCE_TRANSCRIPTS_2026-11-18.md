# 분류 안정화 — 합성 서비스 재평가 원문

2026-11-18. 운영 사용자 기록이 아닌 합성 H2 대화다. 최초 실패와 사전 지정 반복을 모두 보존한다.

원본: `target\chat-quality\exploratory-20261118090715892892600.json`

반복별로 사용자와 대화방을 새로 만들었으며 각 mood-and-change 안에서는 실제 이전 답변과 서버 참조를 재사용했다.

## 반복 1 · mood-and-change · mood-runtime

질문: 우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고

상태: COMPLETED · 자동 통과: True · 지연: 8218ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [1] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 추천해줘 · 우울해서 기분 전환 · 한 시간 반 이하 · 공포는 빼고
> 분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요.
>
> 친구의 도전 (ID: 1)
> 영화 · 2015-01-01 · comedy,drama
> 러닝타임: 90분
> 카탈로그 근거: 유쾌한 친구들이 서로 도우며 희망을 찾는다.
> 추천 이유(모델의 해석): 유쾌한 코미디·드라마와 희망적인 이야기가 기분 전환에 잘 맞고, 90분입니다.
> 카탈로그 줄거리: 유쾌한 친구들이 서로 도우며 희망을 찾는다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화 추천해줘",
      "value": "MOVIE"
    },
    {
      "terms": [
        "코미디",
        "유쾌"
      ],
      "kind": "MOOD",
      "source": "우울해서 기분 전환",
      "value": "우울할 때 기분 전환이 되는 밝고 유쾌한 분위기"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 1 · mood-and-change · shorter

질문: 그중 더 짧은 걸로 골라줘

상태: COMPLETED · 자동 통과: True · 지연: 11751ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [1] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 · 우울해서 기분 전환 · 한 시간 반 이하 · 공포는 빼고 · 그중 · 더 짧은
> 분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요.
>
> 친구의 도전 (ID: 1)
> 영화 · 2015-01-01 · comedy,drama
> 러닝타임: 90분
> 카탈로그 근거: 유쾌한 친구들이 서로 도우며 희망을 찾는다.
> 추천 이유(모델의 해석): 비교할 후보가 하나뿐이라 이 작품을 골랐어요.
> 카탈로그 줄거리: 유쾌한 친구들이 서로 도우며 희망을 찾는다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화",
      "value": "MOVIE"
    },
    {
      "terms": [
        "희망",
        "유쾌",
        "코미디"
      ],
      "kind": "SITUATION",
      "source": "우울해서 기분 전환",
      "value": "우울할 때 기분 전환이 되는 작품"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    },
    {
      "terms": [],
      "kind": "REFERENCE",
      "source": "그중",
      "value": "ONLY_LAST"
    },
    {
      "terms": [],
      "kind": "ORDER",
      "source": "더 짧은",
      "value": "SHORTER_MOVIE"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 1 · mood-and-change · reset-action

질문: 이전 조건은 모두 잊고 액션 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 7679ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [12] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 추천해줘 · 액션
>
> 도약 (ID: 12)
> 영화 · 2023-01-01 · action,adventure
> 러닝타임: 100분
> 카탈로그 근거: action,adventure
> 추천 이유(모델의 해석): 액션 장르의 모험 영화예요.
> 카탈로그 줄거리: 모험가들이 위기를 돌파하며 동료를 구한다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화 추천해줘",
      "value": "MOVIE"
    },
    {
      "terms": [],
      "kind": "GENRE_ALL",
      "source": "액션",
      "value": "action"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 1 · compound · compound-list-personal

질문: 내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 5474ms · 제공자 호출: 1

경로: ['LIST_BOOKMARK', 'PERSONALIZED'] · 공개 참조: [] · 검사 결과: []

> 본인 반응 목록(답변 시점): 복합 요청
>
> [1] 본인 반응 목록(답변 시점): 찜 · 작품
> 최근 등록 순으로 최대 20편을 표시해요. 답변 시점의 기록이에요.
>
> 성탄절 편지 (ID: 3) · 영화
>
> 현재 조회 조건에 해당하는 기록을 모두 표시했어요.
>
> [2] 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 새 영화 추천해줘
> 개인화 기준(답변 시점): 최근 좋아요·찜·싫어요의 장르 점수를 반영했어요. 이미 좋아요·찜·싫어요한 작품은 제외했어요.
>
> 봄날의 정원 (ID: 13)
> 영화 · 2016-01-01 · drama
> 러닝타임: 95분
> 추천 이유(답변 시점): 좋아요·찜에서 얻은 장르 선호와 일치: drama (싫어요 감점 포함 장르 점수 5).
> 카탈로그 줄거리: 봄의 정원에서 꽃을 가꾸며 새로운 시작과 희망을 찾는다.
>
> 여름의 해변 (ID: 7)
> 영화 · 2018-01-01 · drama
> 러닝타임: 100분
> 추천 이유(답변 시점): 좋아요·찜에서 얻은 장르 선호와 일치: drama (싫어요 감점 포함 장르 점수 5).
> 카탈로그 줄거리: 여름 바다와 해변에서 친구들과 추억을 만드는 여행.
>
> 같은 제목 (ID: 14)
> 영화 · 2010-01-01 · drama
> 러닝타임: 100분
> 추천 이유(답변 시점): 좋아요·찜에서 얻은 장르 선호와 일치: drama (싫어요 감점 포함 장르 점수 5).
> 카탈로그 줄거리: 가족의 일상을 그린다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "새 영화 추천해줘",
      "value": "MOVIE"
    }
  ],
  "requests": [
    {
      "route": "LIST_BOOKMARK",
      "media": "ANY"
    },
    {
      "route": "PERSONALIZED"
    }
  ],
  "splitAfter": "보여줘.",
  "comparison": false
}
```

</details>

## 반복 2 · mood-and-change · mood-runtime

질문: 우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고

상태: COMPLETED · 자동 통과: True · 지연: 7612ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [1] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 추천해줘 · 우울해서 기분 전환 · 한 시간 반 이하 · 공포는 빼고
> 분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요.
>
> 친구의 도전 (ID: 1)
> 영화 · 2015-01-01 · comedy,drama
> 러닝타임: 90분
> 카탈로그 근거: 유쾌한 친구들이 서로 도우며 희망을 찾는다.
> 추천 이유(모델의 해석): 친구들의 유쾌한 이야기와 서로 돕는 모습이 기분 전환에 어울려요.
> 카탈로그 줄거리: 유쾌한 친구들이 서로 도우며 희망을 찾는다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화 추천해줘",
      "value": "MOVIE"
    },
    {
      "terms": [
        "코미디",
        "기분 전환"
      ],
      "kind": "MOOD",
      "source": "우울해서 기분 전환",
      "value": "우울할 때 기분 전환되는 작품"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 2 · mood-and-change · shorter

질문: 그중 더 짧은 걸로 골라줘

상태: COMPLETED · 자동 통과: True · 지연: 9897ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [1] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 · 우울해서 기분 전환 · 한 시간 반 이하 · 공포는 빼고 · 그중 · 더 짧은
> 분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요.
>
> 친구의 도전 (ID: 1)
> 영화 · 2015-01-01 · comedy,drama
> 러닝타임: 90분
> 카탈로그 근거: 유쾌한 친구들이 서로 도우며 희망을 찾는다.
> 추천 이유(모델의 해석): 현재 조건에 맞는 후보 중 이 작품을 고를 수 있어요. 러닝타임은 90분입니다.
> 카탈로그 줄거리: 유쾌한 친구들이 서로 도우며 희망을 찾는다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화",
      "value": "MOVIE"
    },
    {
      "terms": [
        "기분 전환",
        "유쾌한",
        "희망"
      ],
      "kind": "MOOD",
      "source": "우울해서 기분 전환",
      "value": "우울할 때 기분 전환"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    },
    {
      "terms": [],
      "kind": "REFERENCE",
      "source": "그중",
      "value": "ONLY_LAST"
    },
    {
      "terms": [],
      "kind": "ORDER",
      "source": "더 짧은",
      "value": "SHORTER_MOVIE"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 2 · mood-and-change · reset-action

질문: 이전 조건은 모두 잊고 액션 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 5408ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [12] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 · 액션
>
> 도약 (ID: 12)
> 영화 · 2023-01-01 · action,adventure
> 러닝타임: 100분
> 카탈로그 근거: action,adventure
> 추천 이유(모델의 해석): 액션과 모험 장르가 명시된 영화예요.
> 카탈로그 줄거리: 모험가들이 위기를 돌파하며 동료를 구한다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화",
      "value": "MOVIE"
    },
    {
      "terms": [],
      "kind": "GENRE_ALL",
      "source": "액션",
      "value": "action"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 2 · compound · compound-list-personal

질문: 내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 4310ms · 제공자 호출: 1

경로: ['LIST_BOOKMARK', 'PERSONALIZED'] · 공개 참조: [] · 검사 결과: []

> 본인 반응 목록(답변 시점): 복합 요청
>
> [1] 본인 반응 목록(답변 시점): 찜 · 작품
> 최근 등록 순으로 최대 20편을 표시해요. 답변 시점의 기록이에요.
>
> 성탄절 편지 (ID: 3) · 영화
>
> 현재 조회 조건에 해당하는 기록을 모두 표시했어요.
>
> [2] 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 · 새
> 개인화 기준(답변 시점): 최근 좋아요·찜·싫어요의 장르 점수를 반영했어요. 이미 좋아요·찜·싫어요한 작품은 제외했어요.
>
> 도약 (ID: 12)
> 영화 · 2023-01-01 · action,adventure
> 러닝타임: 100분
> 추천 이유(답변 시점): 긍정적인 장르 점수 일치가 없어 요청 조건과 카탈로그 평점·조회수를 기준으로 골랐어요.
> 카탈로그 줄거리: 모험가들이 위기를 돌파하며 동료를 구한다.
>
> 웃음 여행 (ID: 2)
> 영화 · 2020-01-01 · comedy
> 러닝타임: 119분
> 추천 이유(답변 시점): 좋아요·찜에서 얻은 장르 선호와 일치: 코미디 (싫어요 감점 포함 장르 점수 3).
> 카탈로그 줄거리: 친구들이 웃음과 희망을 찾아 여행한다.
>
> 길이 미등록 다큐 (ID: 10)
> 영화 · 2020-01-01 · documentary
> 추천 이유(답변 시점): 긍정적인 장르 점수 일치가 없어 요청 조건과 카탈로그 평점·조회수를 기준으로 골랐어요.
> 카탈로그 줄거리: 자연을 관찰하는 기록.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화",
      "value": "MOVIE"
    },
    {
      "terms": [],
      "kind": "ORDER",
      "source": "새",
      "value": "NEWER"
    }
  ],
  "requests": [
    {
      "route": "LIST_BOOKMARK",
      "media": "ANY"
    },
    {
      "route": "PERSONALIZED"
    }
  ],
  "splitAfter": "보여줘.",
  "comparison": false
}
```

</details>

## 반복 3 · mood-and-change · mood-runtime

질문: 우울해서 기분 전환할 영화 추천해줘. 한 시간 반 이하로, 공포는 빼고

상태: COMPLETED · 자동 통과: True · 지연: 8541ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [1] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 영화 · 우울해서 기분 전환 · 한 시간 반 이하 · 공포는 빼고
> 분위기·감상 상황에 대한 적합성은 카탈로그 근거를 바탕으로 한 해석이에요.
>
> 친구의 도전 (ID: 1)
> 영화 · 2015-01-01 · comedy,drama
> 러닝타임: 90분
> 카탈로그 근거: 유쾌한 친구들이 서로 도우며 희망을 찾는다.
> 추천 이유(모델의 해석): 유쾌한 친구들의 이야기로 기분 전환에 어울리고, 러닝타임은 90분입니다.
> 카탈로그 줄거리: 유쾌한 친구들이 서로 도우며 희망을 찾는다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "영화",
      "value": "MOVIE"
    },
    {
      "terms": [
        "코미디",
        "유쾌함"
      ],
      "kind": "MOOD",
      "source": "우울해서 기분 전환",
      "value": "우울할 때 기분 전환이 되는 밝고 유쾌한 작품"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 3 · mood-and-change · shorter

질문: 그중 더 짧은 걸로 골라줘

상태: COMPLETED · 자동 통과: False · 지연: 10553ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [] · 검사 결과: ['EXPECTED_NONEMPTY_REFERENCES']

> 요청 조건과 확인 가능한 카탈로그 정보가 함께 맞는 작품을 찾지 못했어요. 러닝타임이 미등록되거나 유효하지 않은 작품은 시간 조건에서 제외했어요. 조건을 완화하거나 다른 조건을 말씀해 주세요.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "우울해서 기분 전환할 영화 추천해줘",
      "value": "MOVIE"
    },
    {
      "terms": [
        "코미디",
        "희망"
      ],
      "kind": "MOOD",
      "source": "우울해서 기분 전환",
      "value": "기분 전환"
    },
    {
      "terms": [],
      "kind": "MINUTES_MAX",
      "source": "한 시간 반 이하",
      "value": "90"
    },
    {
      "terms": [],
      "kind": "GENRE_EXCLUDE",
      "source": "공포는 빼고",
      "value": "horror"
    },
    {
      "terms": [],
      "kind": "REFERENCE",
      "source": "그중",
      "value": "ONLY_LAST"
    },
    {
      "terms": [],
      "kind": "ORDER",
      "source": "더 짧은",
      "value": "SHORTER_MOVIE"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 3 · mood-and-change · reset-action

질문: 이전 조건은 모두 잊고 액션 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 5415ms · 제공자 호출: 2

경로: ['RECOMMEND'] · 공개 참조: [12] · 검사 결과: []

> 현재 카탈로그에서 찾은 추천 작품이에요.
> 적용한 요청 조건: 액션 영화 · 액션
>
> 도약 (ID: 12)
> 영화 · 2023-01-01 · action,adventure
> 러닝타임: 100분
> 카탈로그 근거: action,adventure
> 추천 이유(모델의 해석): 액션·모험 장르의 영화예요.
> 카탈로그 줄거리: 모험가들이 위기를 돌파하며 동료를 구한다.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [
    {
      "terms": [],
      "kind": "MEDIA",
      "source": "액션 영화",
      "value": "MOVIE"
    },
    {
      "terms": [],
      "kind": "GENRE_ALL",
      "source": "액션",
      "value": "action"
    }
  ],
  "requests": [
    {
      "route": "RECOMMEND"
    }
  ],
  "splitAfter": null,
  "comparison": false
}
```

</details>

## 반복 3 · compound · compound-list-personal

질문: 내 찜 목록을 보여줘. 그리고 내 취향으로 새 영화 추천해줘

상태: COMPLETED · 자동 통과: True · 지연: 2988ms · 제공자 호출: 1

경로: ['LIST_BOOKMARK', 'PERSONALIZED'] · 공개 참조: [] · 검사 결과: []

> 본인 반응 목록(답변 시점): 복합 요청
>
> [1] 본인 반응 목록(답변 시점): 찜 · 작품
> 최근 등록 순으로 최대 20편을 표시해요. 답변 시점의 기록이에요.
>
> 성탄절 편지 (ID: 3) · 영화
>
> 현재 조회 조건에 해당하는 기록을 모두 표시했어요.
>
> [2] 이미 반응한 작품을 제외한 카탈로그에서 조건에 맞는 새 추천을 찾지 못했어요. 장르나 영화/시리즈 조건을 바꿔 주세요.

<details><summary>합성 분류 출력</summary>

```json
{
  "constraints": [],
  "requests": [
    {
      "route": "LIST_BOOKMARK",
      "media": "ANY"
    },
    {
      "route": "PERSONALIZED"
    }
  ],
  "splitAfter": "보여줘.",
  "comparison": false
}
```

</details>

