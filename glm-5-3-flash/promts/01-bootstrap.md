# Промт 1 — Скелет проекта и SSH-ключи

```text
Ты – DevOps-инженер. Рабочая директория: /Users/pabelyakov/Projects/llm-test/z-ai. Ничего не удаляй из существующего.

ЗАДАЧА: создать структуру проекта и ключи доступа для будущих VM в ProxMox.

1. Создай директории: terraform/, ansible/, service/, security/.
2. В security/ сгенерируй пару SSH-ключей ed25519 без парольной фразы:
   ssh-keygen -t ed25519 -f security/deploy_key -N "" -C "proxmox-vms-deploy"
3. Создай security/README.md: назначение ключей, как подключаться (ssh -i), правило не коммитить приватные ключи.
4. Создай .gitignore в корне: security/deploy_key, security/*.pub (кроме README), .terraform/, *.tfstate*, target/, *.iml, .idea/, .DS_Store.
5. Создай ansible.cfg в ansible/: inventory=inventory/hosts.ini, host_key_checking=False, private_key_file=../security/deploy_key, forks=10.
6. Создай корневой README.md — каркас: название проекта, структура директорий, порядок запуска (Terraform → Ansible → Service), статусы разделов «в работе».

КРИТЕРИИ ПРИЁМКИ: директории созданы, ключи сгенерированы, `ssh-keygen -y -f security/deploy_key` возвращает публичный ключ.
Отчитайся списком созданных файлов. При ошибках диагностируй сам и исправляй.
```
