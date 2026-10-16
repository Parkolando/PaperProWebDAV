// rclone for the Ridi Paper Pro (Android 4.4): the stock rclone command with one change.
//
// Android has no /etc/resolv.conf, so Go's resolver would ask [::1]:53 and fail.
// The launcher passes the system DNS server in RCLONE_ANDROID_DNS instead.
package main

import (
	"context"
	"net"
	"os"

	_ "github.com/rclone/rclone/backend/all" // import all backends
	"github.com/rclone/rclone/cmd"
	_ "github.com/rclone/rclone/cmd/all"    // import all commands
	_ "github.com/rclone/rclone/lib/plugin" // import plugins
)

func init() {
	server := os.Getenv("RCLONE_ANDROID_DNS")
	if server == "" {
		return
	}
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(ctx context.Context, network, _ string) (net.Conn, error) {
		var dialer net.Dialer
		return dialer.DialContext(ctx, network, net.JoinHostPort(server, "53"))
	}}
}

func main() {
	cmd.Main()
}
