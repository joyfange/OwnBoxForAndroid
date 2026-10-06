package libcore

import (
	"encoding/json"
	"sort"

	"github.com/sagernet/sing-box/common/trafficcontrol"
	"github.com/sagernet/sing/service"

	"github.com/gofrs/uuid/v5"
)

// Connection viewer (sing-box / Throne style "Connections" screen).
//
// The main BoxInstance is created with a PlatformLogWriter, so the official
// core always registers a trafficcontrol.Manager in the box context
// (box.go: `if needClashAPI || needAPIService || options.PlatformLogWriter != nil`).
// That is the same tracker the Clash API /connections endpoint reads, so the
// list works whether or not the user enabled the Clash API controller.
//
// Results are returned as a JSON string to keep the gomobile surface small
// (only string / bool / int types cross the binding).

const (
	ConnectionsFilterActive int32 = 1
	ConnectionsFilterClosed int32 = 2
	ConnectionsFilterAll    int32 = 3
)

type connectionInfo struct {
	ID            string   `json:"id"`
	Inbound       string   `json:"inbound"`
	InboundType   string   `json:"inboundType"`
	IPVersion     int32    `json:"ipVersion"`
	Network       string   `json:"network"`
	Source        string   `json:"source"`
	Destination   string   `json:"destination"`
	Domain        string   `json:"domain"`
	Protocol      string   `json:"protocol"`
	User          string   `json:"user"`
	FromOutbound  string   `json:"fromOutbound"`
	CreatedAt     int64    `json:"createdAt"`
	ClosedAt      int64    `json:"closedAt"`
	Upload        int64    `json:"upload"`
	Download      int64    `json:"download"`
	Rule          string   `json:"rule"`
	Outbound      string   `json:"outbound"`
	OutboundType  string   `json:"outboundType"`
	Chain         []string `json:"chain"`
	ProcessID     int64    `json:"processId"`
	UserID        int32    `json:"userId"`
	UserName      string   `json:"userName"`
	ProcessPath   string   `json:"processPath"`
	PackageNames  []string `json:"packageNames"`
}

type connectionsSnapshot struct {
	UploadTotal   int64            `json:"uploadTotal"`
	DownloadTotal int64            `json:"downloadTotal"`
	Connections   []connectionInfo `json:"connections"`
}

func (b *BoxInstance) trafficManager() *trafficcontrol.Manager {
	if b == nil || b.ctx == nil {
		return nil
	}
	return service.PtrFromContext[trafficcontrol.Manager](b.ctx)
}

func newConnectionInfo(m *trafficcontrol.TrackerMetadata) connectionInfo {
	md := m.Metadata
	info := connectionInfo{
		ID:           m.ID.String(),
		Inbound:      md.Inbound,
		InboundType:  md.InboundType,
		IPVersion:    int32(md.IPVersion),
		Network:      md.Network,
		Source:       md.Source.String(),
		Destination:  md.Destination.String(),
		Domain:       md.Domain,
		Protocol:     md.Protocol,
		User:         md.User,
		FromOutbound: md.Outbound,
		CreatedAt:    m.CreatedAt.UnixMilli(),
		Outbound:     m.Outbound,
		OutboundType: m.OutboundType,
		Chain:        m.Chain,
		UserID:       -1,
	}
	if info.Domain == "" && md.Destination.IsFqdn() {
		info.Domain = md.Destination.Fqdn
	}
	if !m.ClosedAt.IsZero() {
		info.ClosedAt = m.ClosedAt.UnixMilli()
	}
	if m.Upload != nil {
		info.Upload = m.Upload.Load()
	}
	if m.Download != nil {
		info.Download = m.Download.Load()
	}
	if m.Rule != nil {
		info.Rule = m.Rule.String() + " => " + m.Rule.Action().String()
	} else {
		info.Rule = "final"
	}
	if p := md.ProcessInfo; p != nil {
		info.ProcessID = int64(p.ProcessID)
		info.UserID = p.UserId
		info.UserName = p.UserName
		if len(p.ProcessPaths) > 0 {
			info.ProcessPath = p.ProcessPaths[0]
		}
		info.PackageNames = p.PackageNames
	}
	return info
}

// QueryConnections returns a JSON snapshot of tracked connections.
// filter: 1 = active, 2 = closed, 3 = all. Newest first.
func (b *BoxInstance) QueryConnections(filter int32) string {
	manager := b.trafficManager()
	snapshot := connectionsSnapshot{Connections: []connectionInfo{}}
	if manager != nil {
		snapshot.UploadTotal, snapshot.DownloadTotal = manager.Total()
		if filter&ConnectionsFilterActive != 0 {
			for _, m := range manager.Connections() {
				snapshot.Connections = append(snapshot.Connections, newConnectionInfo(m))
			}
		}
		if filter&ConnectionsFilterClosed != 0 {
			for _, m := range manager.ClosedConnections() {
				snapshot.Connections = append(snapshot.Connections, newConnectionInfo(m))
			}
		}
		sort.SliceStable(snapshot.Connections, func(i, j int) bool {
			return snapshot.Connections[i].CreatedAt > snapshot.Connections[j].CreatedAt
		})
	}
	content, err := json.Marshal(snapshot)
	if err != nil {
		return `{"connections":[]}`
	}
	return string(content)
}

// CloseConnection closes one active connection by its tracker id.
func (b *BoxInstance) CloseConnection(id string) bool {
	manager := b.trafficManager()
	if manager == nil {
		return false
	}
	tracker := manager.Connection(uuid.FromStringOrNil(id))
	if tracker == nil {
		return false
	}
	_ = tracker.Close()
	return true
}

// CloseAllConnections closes every tracked active connection (same as the
// Clash API DELETE /connections, without the network reset).
func (b *BoxInstance) CloseAllConnections() {
	manager := b.trafficManager()
	if manager == nil {
		return
	}
	manager.CloseAllConnections()
}
