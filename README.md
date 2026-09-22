# Guilds — Mod de Guilds para Fabric 1.21.1 (server-side)

Mod completo de guilds para servidores Fabric 1.21.1. **Solo se instala en el
servidor** — los jugadores entran con su cliente vanilla normal.

## Características

- 👑 **Rangos editables**: hasta 8 rangos por guild con 12 permisos granulares
  (invitar, expulsar, promover, banco×3, mejoras, homes×2, editar info, gestionar rangos).
- 🏦 **Banco de guild**: depositar, retirar y transferir dinero, todo controlado
  por los permisos del rango de cada miembro.
- ⭐ **Niveles y mejoras**: XP por invitaciones y por dinero depositado; compra
  con el banco de mejoras (slots de miembros, homes, capacidad del banco,
  interés diario, protección fiscal).
- 🏠 **Homes de guild**: crear, borrar y viajar; quién gestiona depende del rango.
- 🖱️ **GUIs intuitivas**: menús de cofre (`/g gui`) con navegación por clics;
  funcionan igual en clientes vanilla.
- 💸 **Impuestos**: sistema administrativo global (ON/OFF, %, intervalo) y por guild.
- 💬 **Chat de guild**: `/g chat <mensaje>` o modo "todo a la guild" con toggle.
- 🛡️ **Panel de staff**: `/g admin` para gestionar guilds, banco, niveles,
  impuestos y consultar la BD.
- 🔌 **Integraciones opcionales** (el mod funciona sin ellas):
  - **Impactor**: usa la economía del servidor si está instalado.
  - **PlaceholderAPI** (TextPlaceholderAPI): registra `%guilds:*%`.
  - **LuckPerms/u otros**: permisos `guilds.*` vía fabric-permissions-api.
- 🗄️ **Base de datos opcional**: MariaDB (recomendado en producción), SQLite
  (archivo local) o **modo sin BD** (`database.type=none`, guarda en JSON local —
  cero infraestructura).

## Instalación

1. Servidor **Fabric 1.21.1** con **Fabric API** instalado.
2. Copia `build/libs/guilds-1.0.0.jar` a la carpeta `mods/` del servidor.
3. Arranca una vez: se genera `config/guilds/config.properties`, las plantillas
   de GUI en `config/guilds/gui/` y los datos en `config/guilds/data/`.
4. (Opcional) Configura una base de datos (ver abajo) y reinicia. Sin tocar
   nada, el mod funciona con almacenamiento JSON local.

### Base de datos (MariaDB recomendado)

```properties
database.type=mariadb
database.host=127.0.0.1
database.port=3306
database.name=guilds
database.user=guilds
database.password=TU_PASSWORD
```

Crea la BD y el usuario (el mod crea las tablas solo):

```sql
CREATE DATABASE guilds CHARACTER SET utf8mb4;
CREATE USER 'guilds'@'localhost' IDENTIFIED BY 'TU_PASSWORD';
GRANT ALL PRIVILEGES ON guilds.* TO 'guilds'@'localhost';
```

Sin base de datos (por defecto) — nada que instalar ni configurar:

```properties
database.type=none
```

Los datos se guardan en `config/guilds/data/guilds.json` (incluye la economía
interna). Puedes migrar a MariaDB más adelante sin perder el formato.

Para pruebas rápidas sin BD externa también puedes usar `database.type=sqlite`
(archivo local).

### Economía

- `economy.mode=auto` (recomendado): usa **Impactor** si está instalado;
  si no, dinero interno del mod.
- `economy.mode=internal`: dinero propio del mod (tabla `guilds_accounts`).
- `economy.mode=impactor`: obliga a Impactor (falla claro si no está).

## Comandos de jugador (`/g` o `/guild`)

```
/g gui                          Menú principal
/g create <nombre> [tag]        Crear guild (coste configurable)
/g info · /g list · /g top · /g bal
/g invite <jugador> · /g join <guild> · /g kick <jugador>
/g promote|demote <jugador>     Subir/bajar rango
/g transfer <jugador>           Transferir liderazgo
/g open                         Alternar entrada libre
/g rename <nombre> · /g settag <tag> · /g setcolor <0-9a-f>
/g setdesc <texto> · /g icon    (ítem en la mano como icono)
/g bank deposit|withdraw <cant>
/g bank transfer <miembro> <cant>
/g home set|del <nombre> · /g home list · /g home <nombre>
/g rank create|delete <nombre> · /g rank list
/g rank perm <rango> <PERMISO> on|off
/g upgrade <SLOTS|HOMES|BANK|INTEREST|TAX_PROTECT>
/g chat <mensaje> · /g chat     (toggle: todo al chat de guild)
/g leave
```

### Permisos internos de rango (editables con /g rank perm o la GUI)

`INVITE, KICK, PROMOTE, BANK_DEPOSIT, BANK_WITHDRAW, BANK_TRANSFER, UPGRADES,
MANAGE_HOMES, USE_HOMES, EDIT_INFO, MANAGE_RANKS`

## Comandos de staff

Permiso: `guilds.admin` (u OP nivel 2+).

```
/g admin reload
/g admin guireload                   Recarga las plantillas de GUI
/g admin db query <SELECT sql>       Consultas de solo lectura
/g admin taxes on|off
/g admin taxes percent <0-100>       % global por defecto
/g admin taxes interval <minutos>
/g admin guild <nombre> info|delete
/g admin guild <nombre> setbank <cant> | setlevel <n> | tax <0-100>
/g admin economy status
```

## GUIs customizables (config/guilds/gui/)

Cada menú vive en su propio archivo dentro de la subcarpeta
`config/guilds/gui/`: `main.gui`, `bank.gui`, `members.gui`, `ranks.gui`,
`rank_editor.gui`, `upgrades.gui` y `homes.gui`. Se exportan con los valores
por defecto al primer arranque; si borras uno o lo dañas, se regenera solo.

Ejemplo (un fragmento de `main.gui`):

```properties
# Cambia título, filas y relleno del cofre
title=&6Guild: {guild}
rows=3
filler.item=GRAY_STAINED_GLASS_PANE

# Mueve el banco al slot 10 con otro icono y nombre
button.bank.slot=10
button.bank.item=EMERALD_BLOCK
button.bank.name=&aBanco de la guild
button.bank.lore=Saldo: {bank}$ | Capacidad: {cap}$
button.bank.action=open_bank
```

Referencia rápida:

- **Propiedades de menú**: `title`, `rows` (1-6), `filler.item`,
  `list.start` + `list.max` (zona del listado dinámico: miembros, rangos,
  permisos o homes según el menú).
- **Botones**: `button.<id>.slot|item|name|lore|action` (el lore usa `|` para
  separar líneas). Colores con `&`.
- **Placeholders**: `{guild} {tag} {level} {members} {slots} {bank} {cap}
  {player} {rank} {state}`.
- **Acciones**: `open_main`, `open_bank`, `open_members`, `open_ranks`,
  `open_upgrades`, `open_homes`, `close`, `refresh`, `toggle_chat`,
  `prompt_rename`, `deposit:<cantidad|all>`, `withdraw:<cantidad|all>`,
  `buy_upgrade:SLOTS|HOMES|BANK|INTEREST|TAX_PROTECT` (este último pinta
  automáticamente nivel y coste en el lore).
- Aplica los cambios en caliente con **`/g admin guireload`** (sin reiniciar).

## Config destacada

| Clave | Default | Descripción |
|---|---|---|
| `database.type` | none | `none` (JSON local), `sqlite` o `mariadb` |
| `guild.creation-cost` | 5000 | Coste de crear guild (dinero personal) |
| `guild.base-member-slots` | 5 | Slots iniciales (+2 por nivel de SLOTS) |
| `guild.max-level` | 50 | Nivel máximo |
| `guild.max-ranks` | 8 | Rangos por guild |
| `xp.base` / `xp.multiplier` | 1000 / 1.35 | Curva de XP por nivel |
| `homes.base` / `homes.max` | 1 / 10 | Homes de guild |
| `taxes.enabled` | false | Impuestos globales ON/OFF |
| `taxes.default-percent` | 0 | % sobre el saldo personal de miembros |
| `taxes.interval-minutes` | 60 | Ciclo de cobro |
| `chat.format` | ... | Formato del chat de guild (`{color} {tag} {rank} {player} {message}`) |

## Compilar desde fuente

```bash
cd guilds
./gradlew build          # requiere Java 21
# jar en build/libs/guilds-1.0.0.jar
```

## Notas técnicas

- Los menús son contenedores vanilla: los clientes sin el mod los ven como cofres.
- Toda la lógica de DB es asíncrona; el hilo del servidor solo toca la caché.
- Los drivers JDBC van embebidos (jar-in-jar): no hay que instalar nada más.
- Placeholders `%guilds:name|tag|tag_colored|level|members|bank|rank%` (requieren
  TextPlaceholderAPI en el servidor).
