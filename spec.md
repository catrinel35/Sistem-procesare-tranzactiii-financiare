# Specificația funcțională comună (varianta A și varianta B)

Ambele variante implementează exact această specificație. Testele din `fintx-contract` o verifică.
Orice valoare de aici se schimbă doar în acest fișier, **simultan** pentru ambele variante.

## 1. Funcționalități

| # | Funcționalitate | Operație în `TransactionService` | Descriere |
|---|---|---|---|
| 1 | Creare cont (monedă) | `openAccount` | Deschide un cont cu tip, monedă și sold inițial. |
| 2 | Depunere de bani | `process` (`DEPOSIT`) | Crește soldul unui cont. |
| 3 | Extragere de bani | `process` (`WITHDRAWAL`) | Scade soldul, cu limită zilnică și comision. |
| 4 | Transfer către alte conturi | `process` (`TRANSFER`) | Mută bani între două conturi **de aceeași monedă**. |
| 5 | Interogare | `getAccount` | Returnează tipul, moneda și soldul curent. |
| 6 | Istoric tranzacții | `getHistory` | Toate cererile care au implicat contul, în ordinea producerii. |
| 7 | Anulare tranzacție | `cancel` | Doar în **24 de ore** de la finalizare. |
| 8 | Schimb valutar | `process` (`EXCHANGE`) | Convertește între două conturi de monede diferite, la curs fix. |

Contractul exact este definit de interfața `TransactionService`; semnăturile se actualizează odată cu codul (vezi secțiunea 11).

## 2. Tipuri

- Tranzacții: `DEPOSIT` (se completează `to`), `WITHDRAWAL` (se completează `from`), `TRANSFER` și `EXCHANGE` (ambele conturi).
- Conturi: `CURRENT`, `SAVINGS`, `BUSINESS`.
- Monede: `RON`, `EUR`, `USD`. Un cont are o singură monedă, fixată la creare. O monedă necunoscută la creare → `IllegalArgumentException`.
- Stări (chitanță și istoric): `COMPLETED`, `REJECTED`, `CANCELLED`.

## 3. Reguli de business (valori fixate)

| Regulă | Valoare |
|---|---|
| Limită zilnică de ieșiri (extrageri + transferuri + schimburi, fără comisioane), pe zi UTC, în moneda contului | 10.000 la `CURRENT` și `BUSINESS`, 5.000 la `SAVINGS` |
| Fraudă | A 6-a ieșire în ultima oră (adică deja 5 finalizate) este respinsă |
| Comision depunere | 0 |
| Comision extragere | 2,00 dacă suma < 100; altfel 0 |
| Comision transfer | `CURRENT` 0,5 %, `BUSINESS` 0,2 %, `SAVINGS` 0; dacă procentul e > 0, minimum 1,00; rotunjire la 2 zecimale (HALF_UP) |
| Comision schimb valutar | 1 % din suma sursă, minimum 1,00, în moneda sursă; rotunjire la 2 zecimale (HALF_UP) |
| Descoperit de cont | Interzis; comisionul se scade din contul sursă, **în plus** față de sumă |
| Fereastra de anulare | 24 de ore de la momentul finalizării tranzacției |

### Cursuri valutare (fixe, în RON per unitate)

| Monedă | Curs |
|---|---|
| `RON` | 1,0000 |
| `EUR` | 5,0000 |
| `USD` | 4,6000 |

Suma primită în contul destinație la schimb valutar:

```
sumă_destinație = sumă_sursă × curs(sursă) / curs(destinație)   # rotunjită la 2 zecimale, HALF_UP
```

Exemplu: 100,00 EUR → RON = 100 × 5,0 / 1,0 = 500,00 RON; comision 1 % = 1,00 EUR, deci sursa scade cu 101,00 EUR.
Cursurile vin dintr-o sursă înlocuibilă (în varianta A: `Adapter`; în varianta B: constante în cod). Cursurile nu se schimbă în timpul rulării.

Simplificare: nu se verifică proprietarul conturilor (câmpul `owner` este informativ), deci schimbul valutar este posibil între oricare două conturi.

## 4. Ordinea validărilor (determină codul de respingere)

1. Idempotență: dacă `requestId` a mai fost procesat, se returnează aceeași chitanță, fără efect nou.
2. Tip necunoscut → `UNKNOWN_TYPE`.
3. Sumă lipsă sau ≤ 0 → `INVALID_AMOUNT`.
4. Transfer sau schimb către același cont → `SAME_ACCOUNT`.
5. Cont inexistent → `ACCOUNT_NOT_FOUND`.
6. Monedă:
   - `DEPOSIT`, `WITHDRAWAL`, `TRANSFER`: moneda cererii diferă de moneda contului (sau a unuia dintre conturi) → `CURRENCY_MISMATCH`.
   - `EXCHANGE`: moneda cererii este moneda contului **sursă**; dacă diferă → `CURRENCY_MISMATCH`; dacă sursa și destinația au aceeași monedă → `SAME_CURRENCY`.
7. Doar pentru ieșiri (`WITHDRAWAL`, `TRANSFER`, `EXCHANGE`): limită zilnică depășită → `DAILY_LIMIT_EXCEEDED`.
8. Doar pentru ieșiri: fraudă → `FRAUD_SUSPECTED`.
9. Sold insuficient pentru sumă + comision → `INSUFFICIENT_FUNDS`.

Se respinge la **primul** pas eșuat. O respingere nu modifică niciun sold și este memorată sub același `requestId`.

## 5. Anulare (`cancel`)

Verificări, în ordine:

1. `requestId` necunoscut → `REJECTED` / `UNKNOWN_REQUEST`.
2. Tranzacția nu este `COMPLETED` (respinsă sau deja anulată) → `REJECTED` / `NOT_CANCELLABLE`.
3. Au trecut **mai mult de 24 de ore** de la finalizare → `REJECTED` / `CANCEL_WINDOW_EXPIRED`. La exact 24 de ore anularea mai este permisă.
4. Destinația nu mai are suma de returnat (depunere, transfer, schimb) → `REJECTED` / `NOT_CANCELLABLE`, fără modificări.

Efecte:

| Tip | Efect al anulării |
|---|---|
| Depunere | Se scade suma din cont. |
| Extragere | Se returnează suma și comisionul. |
| Transfer | Se scade suma din destinație; sursa primește înapoi suma și comisionul. |
| Schimb valutar | Se scade din destinație suma primită inițial; sursa primește înapoi suma și comisionul (la valorile originale, nu se recalculează cursul). |

- O tranzacție anulată nu mai contează la limita zilnică și la verificarea de fraudă.
- Chitanța de anulare are `status = CANCELLED` și același `requestId` ca tranzacția originală.
- Momentul „acum" provine din ceasul serviciului (vezi secțiunea 9), deci fereastra de 24 de ore se poate testa fără așteptare.

## 6. Interogare

`getAccount(accountId)` returnează `Optional<AccountView>` (id, tip, monedă, sold); gol dacă nu există.

## 7. Istoric tranzacții

`getHistory(accountId)` returnează, în ordinea producerii, câte o intrare pentru fiecare eveniment care a implicat contul:

- Fiecare cerere procesată produce câte o intrare pentru fiecare cont **existent** implicat: `DEPOSIT` → `to`; `WITHDRAWAL` → `from`; `TRANSFER` și `EXCHANGE` → ambele.
- `event`: `COMPLETED`, `REJECTED` sau `CANCELLED`. La respingere, `detail` = codul motivului; la anulare, `detail` = motivul anulării.
- Cererile idempotente (repetate) nu produc intrări noi.
- Cont inexistent → listă goală.
- Intrarea conține: moment, `requestId`, cont, eveniment, detaliu.

Observație: în varianta A acest istoric este implementat prin `Decorator`/`Observer` (audit), iar în varianta B prin liste și `System.out` împrăștiate în cod; comparația de la H5 măsoară acoperirea lui.

## 8. Concurență

Sub accesări simultane trebuie păstrate: nicio depunere pierdută, nicio intrare de istoric pierdută și suma totală a banilor din sistem constantă la transferuri fără comision. Testele corespunzătoare au tag-ul `concurrency` (`mvn test -Pconcurrency`).

## 9. Ceasul serviciului

Orice implementare primește un `java.time.Clock` la construire (implicit `Clock.systemUTC()`). Toate momentele (chitanțe, istoric, limita zilnică, fraudă, fereastra de anulare) se calculează din acest ceas. Testele folosesc un ceas controlabil pentru scenariile „după 24 de ore" și „a doua zi".

## 10. Coduri de respingere

`UNKNOWN_TYPE`, `INVALID_AMOUNT`, `SAME_ACCOUNT`, `ACCOUNT_NOT_FOUND`, `CURRENCY_MISMATCH`, `SAME_CURRENCY`,
`DAILY_LIMIT_EXCEEDED`, `FRAUD_SUSPECTED`, `INSUFFICIENT_FUNDS`, `UNKNOWN_REQUEST`, `NOT_CANCELLABLE`, `CANCEL_WINDOW_EXPIRED`.

## 11. Aliniere cu codul existent

Codul din `fintx-spec`, `fintx-contract` și `fintx-bad` urmează această specificație:

| Cerință | Unde este implementată |
|---|---|
| Tip `EXCHANGE`, cursuri, comision de schimb, coduri `SAME_CURRENCY` și `CANCEL_WINDOW_EXPIRED` | `Codes`, `BadTransactionManager` |
| `getHistory` și `HistoryEntry` (au înlocuit `getAudit` și `AuditRecord`) | `TransactionService`, `HistoryEntry`, teste |
| Ceas injectabil (`Clock`) | constructorul `BadTransactionManager(Clock)`; `create(Clock)` și `MutableClock` în teste |
| Anulare în 24 h | `BadTransactionManager.cancel` |
| Teste noi: schimb valutar, fereastra de anulare, istoric pentru `EXCHANGE`, monedă necunoscută, resetarea limitei zilnice, expirarea ferestrei de fraudă | `TransactionServiceContractTest` |

Rămâne de făcut: varianta A (`fintx-patterns`) trebuie să implementeze aceeași specificație și să treacă aceleași teste.
