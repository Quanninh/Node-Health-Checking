$DefaultInstances = 8
$DefaultMaxNeighbors = 4
$DefaultInterface = "wireless_32768"
$DefaultBindHost = "0.0.0.0"

$AutoIp = Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object {
        $_.IPAddress -notlike "127.*" -and
        $_.IPAddress -notlike "169.254.*"
    } |
    Select-Object -First 1 -ExpandProperty IPAddress

if (-not $AutoIp) {
    $AutoIp = "127.0.0.1"
}

$InstancesInput = Read-Host "Number of instances [$DefaultInstances]"
$Instances = if ([string]::IsNullOrWhiteSpace($InstancesInput)) { $DefaultInstances } else { [int]$InstancesInput }

$MaxNeighborsInput = Read-Host "Max neighbors [$DefaultMaxNeighbors]"
$MaxNeighbors = if ([string]::IsNullOrWhiteSpace($MaxNeighborsInput)) { $DefaultMaxNeighbors } else { [int]$MaxNeighborsInput }

$InterfaceInput = Read-Host "Multicast interface [$DefaultInterface]"
$MulticastInterface = if ([string]::IsNullOrWhiteSpace($InterfaceInput)) { $DefaultInterface } else { $InterfaceInput }

$BindHostInput = Read-Host "Bind host [$DefaultBindHost]"
$BindHost = if ([string]::IsNullOrWhiteSpace($BindHostInput)) { $DefaultBindHost } else { $BindHostInput }

$AdvertiseHostInput = Read-Host "Advertise host [$AutoIp]"
$AdvertiseHost = if ([string]::IsNullOrWhiteSpace($AdvertiseHostInput)) { $AutoIp } else { $AdvertiseHostInput }

for ($i = 1; $i -le $Instances; $i++) {
    $NodeId = "node-$i"

    $Command = "cd node-agent; mvn exec:java '-Dexec.mainClass=com.monitoring.agent.App' '-Dexec.args=--bind-host $BindHost --advertise-host $AdvertiseHost --max-neighbors $MaxNeighbors --multicast-interface $MulticastInterface --node-id $NodeId'"

    Write-Host "Starting $NodeId"

    Start-Process powershell.exe -ArgumentList "-NoExit", "-Command", $Command

    Start-Sleep -Milliseconds 5000
}