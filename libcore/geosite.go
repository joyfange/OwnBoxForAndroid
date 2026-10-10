package libcore

import (
	"fmt"
	"os"

	geosites "github.com/sagernet/sing-box/common/geosite"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

type geosite struct {
	geositeReader *geosites.Reader
}

func (g *geosite) Open(path string) error {
	geositeReader, _, err := geosites.Open(path)
	g.geositeReader = geositeReader
	return err
}

func (g *geosite) Rules(code string) ([]option.HeadlessRule, error) {
	sourceSet, err := g.geositeReader.Read(code)
	if err != nil {
		return nil, fmt.Errorf("failed to read geosite code %s :%w", code, err)
	}

	var headlessRule option.DefaultHeadlessRule

	defaultRule := geosites.Compile(sourceSet)

	headlessRule.Domain = defaultRule.Domain
	headlessRule.DomainSuffix = defaultRule.DomainSuffix
	headlessRule.DomainKeyword = defaultRule.DomainKeyword
	headlessRule.DomainRegex = defaultRule.DomainRegex

	return []option.HeadlessRule{
		{
			Type:           C.RuleTypeDefault,
			DefaultOptions: headlessRule,
		},
	}, nil
}

// loadGeoSiteRules 从 geosite.db 读取指定代码的规则
// （替代 fork 的 nekoutils.GetGeoSiteHeadlessRules 钩子）。
func loadGeoSiteRules(dbPath string, code string) ([]option.HeadlessRule, error) {
	// Open the file here so it is closed afterwards (geosite.Open leaves it open for good).
	file, err := os.Open(dbPath)
	if err != nil {
		return nil, err
	}
	defer file.Close()
	reader, _, err := geosites.NewReader(file)
	if err != nil {
		return nil, err
	}
	g := &geosite{geositeReader: reader}
	return g.Rules(code)
}
