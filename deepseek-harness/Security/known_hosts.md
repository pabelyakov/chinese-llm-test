# known_hosts — фиксация fingerprint хостов

## Зачем

После раскатки VM их SSH fingerprint нужно зафиксировать, чтобы Ansible/ssh не
спрашивали подтверждение и подключение было защищено от MITM.

## Получить fingerprint хоста

```bash
ssh-keyscan -t ed25519 <host> 2>/dev/null
```

## Добавить хост в known_hosts (после раскатки)

Вариант А — сразу записать в `Security/known_hosts` (общий файл для проекта):

```bash
ssh-keyscan -t ed25519,rsa -H <host> >> Security/known_hosts
```

Вариант Б — в пользовательский `~/.ssh/known_hosts`:

```bash
ssh-keyscan -t ed25519,rsa -H <host> >> ~/.ssh/known_hosts
```

Вариант В — для Ansible задать в `ansible.cfg`:

```ini
[defaults]
host_key_checking = False
# НЕ рекомендуется для production — лучше зафиксировать known_hosts
```

## Проверка fingerprint (ручная)

```bash
# на VM (или через console): fingerprint сервера
ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub
```

Сравнить со значением на клиенте:

```bash
ssh-keygen -lf Security/id_ed25519.pub
```

## Массово для всех VM после раскатки

```bash
for host in <host1> <host2> ... ; do
  ssh-keyscan -t ed25519,rsa -H "$host" >> Security/known_hosts
done
```

> `Security/known_hosts` можно коммитить (публичные fingerprint не секрет).
> Если хост пересоздаётся — удали старую запись, иначе ssh выдаст
> `REMOTE HOST IDENTIFICATION HAS CHANGED`.
