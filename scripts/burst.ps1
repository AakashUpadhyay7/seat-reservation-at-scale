param(
  [Parameter(Mandatory=$true)][string]$BaseUrl,
  [Parameter(Mandatory=$true)][string]$ShowId,
  [ValidateSet('hot-seat','idempotency','per-user')][string]$Mode = 'hot-seat',
  [int]$Requests = 100,
  [string]$Seat = 'A1',
  [string]$AuthSecret = 'local-secret'
)

$BaseUrl = $BaseUrl.TrimEnd('/')
$client = [System.Net.Http.HttpClient]::new()
$tasks = [System.Collections.Generic.List[System.Threading.Tasks.Task[object]]]::new()
$sharedKey = "burst-$([guid]::NewGuid())"

for ($i = 0; $i -lt $Requests; $i++) {
  $user = if ($Mode -eq 'per-user') { 'limit-user' } else { "burst-user-$i" }
  $key = if ($Mode -eq 'idempotency') { $sharedKey } else { "burst-$([guid]::NewGuid())" }
  $seatName = if ($Mode -eq 'per-user') { "P$($i + 1)" } else { $Seat }
  $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post, "$BaseUrl/shows/$ShowId/reserve")
  $request.Headers.TryAddWithoutValidation('Authorization', "Bearer user:$user`:$AuthSecret") | Out-Null
  $request.Headers.TryAddWithoutValidation('Idempotency-Key', $key) | Out-Null
  $request.Content = [System.Net.Http.StringContent]::new((ConvertTo-Json @{ seats = @($seatName) }), [System.Text.Encoding]::UTF8, 'application/json')

  $tasks.Add($client.SendAsync($request).ContinueWith({ param($t) [pscustomobject]@{ Status = [int]$t.Result.StatusCode; Body = $t.Result.Content.ReadAsStringAsync().Result } }))
}

$results = [System.Threading.Tasks.Task]::WhenAll($tasks).GetAwaiter().GetResult()
$counts = @{}
foreach ($r in $results) {
  try { $code = (ConvertFrom-Json $r.Body).code } catch { $code = if ($r.Status -eq 201) { 'confirmed' } else { 'unknown' } }
  $key = "$($r.Status):$code"
  if (-not $counts.ContainsKey($key)) { $counts[$key] = 0 }
  $counts[$key]++
}

$showResponse = $client.GetStringAsync("$BaseUrl/shows/$ShowId").GetAwaiter().GetResult() | ConvertFrom-Json
$sum = $showResponse.available + $showResponse.held + $showResponse.confirmed
$result = [ordered]@{
  mode = $Mode
  requests = $Requests
  outcomes = $counts
  reconciliation = [ordered]@{
    total = $showResponse.total_seats
    available = $showResponse.available
    held = $showResponse.held
    confirmed = $showResponse.confirmed
    sum = $sum
    invariant_ok = ($sum -eq $showResponse.total_seats)
  }
}
$result | ConvertTo-Json -Depth 6
if (-not $result.reconciliation.invariant_ok -or @($results | Where-Object { $_.Status -ge 500 }).Count -gt 0) { exit 1 }
