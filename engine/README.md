# Пакет движка Windows

`VERSION` — номер пакета движка. Если изменить число и отправить на GitHub, workflow
«Пакет движка Windows» (`.github/workflows/engine.yml`) соберёт новый пакет и опубликует
его в Releases под тегом `engine-N`. Номер должен совпадать с `EngineManager.PACKAGE` в приложении.
